package com.liferay.user.group.recommendations;

import com.liferay.blogs.model.BlogsEntry;
import com.liferay.blogs.service.BlogsEntryLocalService;
import com.liferay.info.collection.provider.CollectionQuery;
import com.liferay.info.collection.provider.InfoCollectionProvider;
import com.liferay.info.pagination.InfoPage;
import com.liferay.info.pagination.Pagination;
import com.liferay.portal.configuration.metatype.bnd.util.ConfigurableUtil;
import com.liferay.portal.kernel.dao.orm.QueryDefinition;
import com.liferay.portal.kernel.language.Language;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.UserGroup;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceContextThreadLocal;
import com.liferay.portal.kernel.service.UserGroupLocalService;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.ResourceBundleUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.user.group.recommendations.configuration.UserGroupRecommendationsConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;

/**
 * Serves a curated set of blog entries chosen per user group, so a Collection
 * Display fragment shows different posts depending on who is looking.
 *
 * <p>
 * <b>Why this exists.</b> A deliberate stand-in for Analytics Cloud / LDP
 * content recommendations, for environments where those cannot be made to work.
 * It is registered as a collection <i>provider</i> rather than shipped as a
 * Collection precisely so that it occupies the same slot in the Collection
 * Display fragment's picker as the recommendation provider it substitutes for:
 * switching between the two is then a dropdown change on the page rather than
 * re-authoring it.
 * </p>
 *
 * <p>
 * <b>What was ruled out first.</b> Liferay already personalises collections by
 * user segment, and segment criteria include User Group membership, so the
 * underlying use case is natively supported and needs no bundle. That route was
 * rejected only because it produces a Collection, which the fragment consumes
 * from a different slot than a Provider, and so cannot be substituted for a
 * recommendation provider. Where swappability does not matter, prefer the
 * native route and do not deploy this.
 * </p>
 *
 * <p>
 * <b>Why the mapping is in OSGi configuration.</b> An earlier revision
 * implemented {@code ConfigurableInfoCollectionProvider} and built the mapping
 * as a form in the page editor. That was withdrawn after it proved
 * undiscoverable in use, and for three reasons that stand regardless: the
 * configuration is stored in the page and so is lost on a site rebuild, it must
 * be repeated for every placement of the fragment, and it can be neither
 * version-controlled nor seeded by a deployment script. See
 * {@link UserGroupRecommendationsConfiguration}.
 * </p>
 *
 * <p>
 * <b>Why {@code BlogsEntry} rather than {@code AssetEntry}.</b> Blogs register a
 * rich item-specific field set through
 * {@code BlogsEntryInfoItemFieldValuesProvider}. Typing the collection to
 * {@code AssetEntry} would resolve fields through the asset provider instead,
 * silently dropping blog-specific fields such as subtitle and cover image from
 * fragment mapping. Liferay types its own recommendation provider to a concrete
 * class for the same reason.
 * </p>
 *
 * @author Peter Richards
 */
@Component(
	configurationPid = "com.liferay.user.group.recommendations.configuration.UserGroupRecommendationsConfiguration",
	property = "item.class.name=com.liferay.blogs.model.BlogsEntry",
	service = InfoCollectionProvider.class
)
public class UserGroupRecommendationsInfoCollectionProvider
	implements InfoCollectionProvider<BlogsEntry> {

	public static final String FALLBACK_EMPTY = "empty";

	public static final String FALLBACK_RECENT = "recent";

	public static final String KEY = "userGroupRecommendations";

	public static final String STRATEGY_FIRST_MATCH = "firstMatch";

	public static final String STRATEGY_UNION = "union";

	@Override
	public InfoPage<BlogsEntry> getCollectionInfoPage(
		CollectionQuery collectionQuery) {

		Pagination pagination = collectionQuery.getPagination();

		try {
			ServiceContext serviceContext =
				ServiceContextThreadLocal.getServiceContext();

			if (serviceContext == null) {
				_log.error(
					"Unable to resolve a service context, so the current " +
						"user cannot be determined");

				return InfoPage.of(Collections.emptyList(), pagination, 0);
			}

			List<BlogsEntry> blogsEntries = _getConfiguredBlogsEntries(
				serviceContext);

			if (blogsEntries.isEmpty()) {
				blogsEntries = _getFallbackBlogsEntries(serviceContext);
			}

			return _paginate(blogsEntries, pagination);
		}
		catch (Exception exception) {
			_log.error(
				"Unable to resolve user group recommendations", exception);

			return InfoPage.of(Collections.emptyList(), pagination, 0);
		}
	}

	@Override
	public String getKey() {
		return KEY;
	}

	@Override
	public String getLabel(Locale locale) {
		return _language.get(
			ResourceBundleUtil.getBundle(
				"content.Language", locale, getClass()),
			"recommended-for-your-group");
	}

	@Activate
	@Modified
	protected void activate(Map<String, Object> properties) {
		_configuration = ConfigurableUtil.createConfigurable(
			UserGroupRecommendationsConfiguration.class, properties);
	}

	/**
	 * Resolves the entries configured for the groups the current user belongs
	 * to.
	 *
	 * <p>
	 * Configuration order is authoritative, not the order
	 * {@code getUserUserGroups} happens to return: the configured lines are
	 * walked in the order they are written, and a line is used only if the user
	 * belongs to that group. That makes {@code firstMatch} mean "the first group
	 * listed in configuration", which an administrator can see and control,
	 * rather than whatever the service returns first.
	 * </p>
	 */
	private List<BlogsEntry> _getConfiguredBlogsEntries(
		ServiceContext serviceContext) {

		String[] userGroupEntries = _configuration.userGroupEntries();

		if ((userGroupEntries == null) || (userGroupEntries.length == 0)) {
			return Collections.emptyList();
		}

		long userId = serviceContext.getUserId();

		if (userId <= 0) {
			return Collections.emptyList();
		}

		Set<String> userGroupNames = new LinkedHashSet<>();

		for (UserGroup userGroup :
				_userGroupLocalService.getUserUserGroups(userId)) {

			userGroupNames.add(StringUtil.toLowerCase(userGroup.getName()));
		}

		if (userGroupNames.isEmpty()) {
			return Collections.emptyList();
		}

		boolean union = STRATEGY_UNION.equals(
			GetterUtil.getString(
				_configuration.multiGroupStrategy(), STRATEGY_FIRST_MATCH));

		// A LinkedHashSet so a union keeps configured order and never repeats
		// an entry that two groups both name.

		Set<String> references = new LinkedHashSet<>();

		for (String userGroupEntry : userGroupEntries) {
			if (Validator.isNull(userGroupEntry)) {
				continue;
			}

			int index = userGroupEntry.indexOf('=');

			if (index <= 0) {
				_log.error(
					"Ignoring malformed configuration line, expected " +
						"<user group name>=<ref>,<ref>: " + userGroupEntry);

				continue;
			}

			String userGroupName = StringUtil.toLowerCase(
				userGroupEntry.substring(0, index).trim());

			if (!userGroupNames.contains(userGroupName)) {
				continue;
			}

			for (String reference :
					userGroupEntry.substring(index + 1).split(",")) {

				reference = reference.trim();

				if (!reference.isEmpty()) {
					references.add(reference);
				}
			}

			if (!union) {
				break;
			}
		}

		return _resolve(references, serviceContext.getScopeGroupId());
	}

	private List<BlogsEntry> _getFallbackBlogsEntries(
		ServiceContext serviceContext) {

		String fallback = GetterUtil.getString(
			_configuration.fallback(), FALLBACK_EMPTY);

		if (!FALLBACK_RECENT.equals(fallback)) {
			return Collections.emptyList();
		}

		return _blogsEntryLocalService.getGroupEntries(
			serviceContext.getScopeGroupId(),
			new QueryDefinition<>(
				WorkflowConstants.STATUS_APPROVED, 0, _FALLBACK_LIMIT, null));
	}

	private InfoPage<BlogsEntry> _paginate(
		List<BlogsEntry> blogsEntries, Pagination pagination) {

		int totalCount = blogsEntries.size();

		int start = Math.min(pagination.getStart(), totalCount);
		int end = Math.min(pagination.getEnd(), totalCount);

		if (start > end) {
			start = end;
		}

		return InfoPage.of(
			blogsEntries.subList(start, end), pagination, totalCount);
	}

	/**
	 * Resolves each reference as an external reference code, then a friendly
	 * URL, then a numeric entry id.
	 *
	 * <p>
	 * The numeric form is last and is a convenience only. Entry ids differ
	 * between environments, so a configuration file written against one
	 * instance resolves to nothing -- or to unrelated entries -- on another,
	 * which is exactly the failure a deployable config file exists to avoid.
	 * </p>
	 *
	 * <p>
	 * Entries that no longer resolve, or are no longer approved, are skipped
	 * rather than surfaced as nulls: a configuration naming a deleted entry
	 * should degrade to a shorter list, not to an error. An unresolvable
	 * reference is logged, because that one is almost always a typo.
	 * </p>
	 */
	private List<BlogsEntry> _resolve(Set<String> references, long groupId) {
		List<BlogsEntry> blogsEntries = new ArrayList<>(references.size());

		for (String reference : references) {
			BlogsEntry blogsEntry =
				_blogsEntryLocalService.fetchBlogsEntryByExternalReferenceCode(
					reference, groupId);

			if (blogsEntry == null) {
				blogsEntry = _blogsEntryLocalService.fetchEntry(
					groupId, reference);
			}

			if ((blogsEntry == null) && Validator.isNumber(reference)) {
				blogsEntry = _blogsEntryLocalService.fetchBlogsEntry(
					GetterUtil.getLong(reference));
			}

			if (blogsEntry == null) {
				_log.error(
					"No blog entry in group " + groupId +
						" matches the configured reference " + reference);

				continue;
			}

			if (blogsEntry.getStatus() != WorkflowConstants.STATUS_APPROVED) {
				continue;
			}

			blogsEntries.add(blogsEntry);
		}

		return blogsEntries;
	}

	private static final int _FALLBACK_LIMIT = 20;

	private static final Log _log = LogFactoryUtil.getLog(
		UserGroupRecommendationsInfoCollectionProvider.class);

	@Reference
	private BlogsEntryLocalService _blogsEntryLocalService;

	private volatile UserGroupRecommendationsConfiguration _configuration;

	@Reference
	private Language _language;

	@Reference
	private UserGroupLocalService _userGroupLocalService;

}
