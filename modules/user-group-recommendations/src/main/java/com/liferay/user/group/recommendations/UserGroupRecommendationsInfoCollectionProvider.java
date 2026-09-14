package com.liferay.user.group.recommendations;

import com.liferay.blogs.model.BlogsEntry;
import com.liferay.blogs.service.BlogsEntryLocalService;
import com.liferay.info.collection.provider.CollectionQuery;
import com.liferay.info.collection.provider.ConfigurableInfoCollectionProvider;
import com.liferay.info.collection.provider.InfoCollectionProvider;
import com.liferay.info.field.InfoField;
import com.liferay.info.field.InfoFieldSet;
import com.liferay.info.field.type.MultiselectInfoFieldType;
import com.liferay.info.field.type.OptionInfoFieldType;
import com.liferay.info.field.type.SelectInfoFieldType;
import com.liferay.info.form.InfoForm;
import com.liferay.info.localized.InfoLocalizedValue;
import com.liferay.info.pagination.InfoPage;
import com.liferay.info.pagination.Pagination;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.dao.orm.QueryDefinition;
import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.language.Language;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.UserGroup;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceContextThreadLocal;
import com.liferay.portal.kernel.service.UserGroupLocalService;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.ListUtil;
import com.liferay.portal.kernel.util.ResourceBundleUtil;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Serves a hand-picked set of blog entries chosen per user group, so a
 * Collection Display fragment shows different posts depending on who is
 * looking.
 *
 * <p>
 * <b>Why this exists.</b> This is a deliberate stand-in for Analytics Cloud /
 * LDP content recommendations, for environments where those cannot be made to
 * work. It is registered as a collection <i>provider</i> rather than shipped as
 * a Collection precisely so that it occupies the same slot in the Collection
 * Display fragment's picker as the recommendation provider it substitutes for:
 * switching between the two is then a dropdown change on the page rather than
 * re-authoring the page.
 * </p>
 *
 * <p>
 * <b>What was ruled out first.</b> Liferay already personalises collections by
 * user segment ("Add Personalized Variation"), and segment criteria include
 * User Group membership, so the underlying use case is natively supported and
 * needs no bundle. That route was rejected here for one specific reason: it
 * produces a <i>Collection</i>, which the fragment consumes from a different
 * slot than a <i>Provider</i>, and so cannot be swapped with a recommendation
 * provider. It also requires Segments. Where swappability does not matter,
 * prefer the native route and do not deploy this.
 * </p>
 *
 * <p>
 * <b>Why {@code BlogsEntry} rather than {@code AssetEntry}.</b> Blogs register
 * a rich item-specific field set through
 * {@code BlogsEntryInfoItemFieldValuesProvider}. Typing the collection to
 * {@code AssetEntry} would resolve fields through the asset provider instead,
 * silently dropping blog-specific fields such as subtitle and cover image from
 * fragment mapping. Liferay types its own recommendation provider to a concrete
 * class for the same reason.
 * </p>
 *
 * <p>
 * <b>Configuration.</b> The mapping is not compiled in. The provider implements
 * {@link ConfigurableInfoCollectionProvider}, and the form is built at request
 * time from the user groups that actually exist, so adding a user group adds a
 * field with no redeploy. Note that the Info framework exposes no field type
 * for picking arbitrary content items -- the available types cover categories,
 * tags and fixed option lists only -- so each field is a multiselect whose
 * options are the site's blog entries, resolved when the form is built.
 * </p>
 *
 * @author Peter Richards
 */
@Component(
	property = "item.class.name=com.liferay.blogs.model.BlogsEntry",
	service = InfoCollectionProvider.class
)
public class UserGroupRecommendationsInfoCollectionProvider
	implements ConfigurableInfoCollectionProvider<BlogsEntry> {

	public static final String KEY = "userGroupRecommendations";

	/**
	 * Prefix for the per-user-group configuration fields. The user group id is
	 * appended, rather than the name, so renaming a user group in the admin UI
	 * does not silently orphan a page's configuration.
	 */
	public static final String USER_GROUP_FIELD_PREFIX = "userGroupId--";

	public static final String FIELD_FALLBACK = "fallback";

	public static final String FIELD_MULTI_GROUP_STRATEGY =
		"multiGroupStrategy";

	public static final String FALLBACK_EMPTY = "empty";

	public static final String FALLBACK_RECENT = "recent";

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
					"Unable to resolve a service context, so the current user " +
						"cannot be determined");

				return InfoPage.of(
					Collections.emptyList(), pagination, 0);
			}

			Map<String, String[]> configuration =
				collectionQuery.getConfiguration();

			List<BlogsEntry> blogsEntries = _getConfiguredBlogsEntries(
				configuration, serviceContext);

			if (blogsEntries.isEmpty()) {
				blogsEntries = _getFallbackBlogsEntries(
					configuration, serviceContext);
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
	public InfoForm getConfigurationInfoForm() {
		InfoFieldSet.Builder userGroupsBuilder = InfoFieldSet.builder();

		for (UserGroup userGroup : _getUserGroups()) {
			userGroupsBuilder.infoFieldSetEntry(
				InfoField.builder(
				).infoFieldType(
					MultiselectInfoFieldType.INSTANCE
				).namespace(
					StringPool.BLANK
				).name(
					USER_GROUP_FIELD_PREFIX + userGroup.getUserGroupId()
				).attribute(
					MultiselectInfoFieldType.OPTIONS, _getBlogsEntryOptions()
				).labelInfoLocalizedValue(
					InfoLocalizedValue.singleValue(userGroup.getName())
				).localizable(
					false
				).build());
		}

		return InfoForm.builder(
		).infoFieldSetEntry(
			userGroupsBuilder.descriptionInfoLocalizedValue(
				InfoLocalizedValue.localize(
					getClass(),
					"choose-the-entries-each-user-group-should-be-shown")
			).labelInfoLocalizedValue(
				InfoLocalizedValue.localize(getClass(), "user-groups")
			).name(
				"userGroups"
			).build()
		).infoFieldSetEntry(
			InfoFieldSet.builder(
			).infoFieldSetEntry(
				_buildSelectInfoField(
					FIELD_MULTI_GROUP_STRATEGY, "when-a-user-matches-several",
					new OptionInfoFieldType(
						true,
						InfoLocalizedValue.localize(
							getClass(), "use-the-first-matching-user-group"),
						STRATEGY_FIRST_MATCH),
					new OptionInfoFieldType(
						InfoLocalizedValue.localize(
							getClass(), "combine-every-matching-user-group"),
						STRATEGY_UNION))
			).infoFieldSetEntry(
				_buildSelectInfoField(
					FIELD_FALLBACK, "when-a-user-matches-none",
					new OptionInfoFieldType(
						true,
						InfoLocalizedValue.localize(
							getClass(), "show-nothing"),
						FALLBACK_EMPTY),
					new OptionInfoFieldType(
						InfoLocalizedValue.localize(
							getClass(), "show-the-most-recent-entries"),
						FALLBACK_RECENT))
			).labelInfoLocalizedValue(
				InfoLocalizedValue.localize(getClass(), "behaviour")
			).name(
				"behaviour"
			).build()
		).build();
	}

	@Override
	public String getKey() {
		return KEY;
	}

	@Override
	public String getLabel(Locale locale) {
		return _language.get(
			ResourceBundleUtil.getBundle("content.Language", locale, getClass()),
			"recommended-for-your-group");
	}

	private InfoField<SelectInfoFieldType> _buildSelectInfoField(
		String name, String labelKey, OptionInfoFieldType... options) {

		return InfoField.builder(
		).infoFieldType(
			SelectInfoFieldType.INSTANCE
		).namespace(
			StringPool.BLANK
		).name(
			name
		).attribute(
			SelectInfoFieldType.INLINE, true
		).attribute(
			SelectInfoFieldType.OPTIONS, ListUtil.fromArray(options)
		).labelInfoLocalizedValue(
			InfoLocalizedValue.localize(getClass(), labelKey)
		).localizable(
			true
		).build();
	}

	private List<OptionInfoFieldType> _getBlogsEntryOptions() {
		ServiceContext serviceContext =
			ServiceContextThreadLocal.getServiceContext();

		if (serviceContext == null) {
			return Collections.emptyList();
		}

		List<OptionInfoFieldType> options = new ArrayList<>();

		for (BlogsEntry blogsEntry :
				_blogsEntryLocalService.getGroupEntries(
					serviceContext.getScopeGroupId(),
					new QueryDefinition<>(
						WorkflowConstants.STATUS_APPROVED, QueryUtil.ALL_POS,
						QueryUtil.ALL_POS, null))) {

			options.add(
				new OptionInfoFieldType(
					InfoLocalizedValue.singleValue(blogsEntry.getTitle()),
					String.valueOf(blogsEntry.getEntryId())));
		}

		return options;
	}

	/**
	 * Resolves the entries configured for the groups the current user belongs
	 * to. Configured order is preserved deliberately: the point of hand-picking
	 * is that the sequence is chosen, so it must not be re-sorted here.
	 */
	private List<BlogsEntry> _getConfiguredBlogsEntries(
		Map<String, String[]> configuration, ServiceContext serviceContext) {

		if ((configuration == null) || configuration.isEmpty()) {
			return Collections.emptyList();
		}

		long userId = serviceContext.getUserId();

		if (userId <= 0) {
			return Collections.emptyList();
		}

		boolean union = STRATEGY_UNION.equals(
			_getSingleValue(
				configuration, FIELD_MULTI_GROUP_STRATEGY,
				STRATEGY_FIRST_MATCH));

		// A LinkedHashSet so that a union across groups keeps configured order
		// and does not show the same entry twice.

		Set<Long> entryIds = new LinkedHashSet<>();

		for (UserGroup userGroup :
				_userGroupLocalService.getUserUserGroups(userId)) {

			String[] configuredEntryIds = configuration.get(
				USER_GROUP_FIELD_PREFIX + userGroup.getUserGroupId());

			if ((configuredEntryIds == null) ||
				(configuredEntryIds.length == 0)) {

				continue;
			}

			for (String configuredEntryId : configuredEntryIds) {
				long entryId = GetterUtil.getLong(configuredEntryId);

				if (entryId > 0) {
					entryIds.add(entryId);
				}
			}

			if (!union) {
				break;
			}
		}

		return _resolve(entryIds);
	}

	private List<BlogsEntry> _getFallbackBlogsEntries(
		Map<String, String[]> configuration, ServiceContext serviceContext) {

		String fallback = _getSingleValue(
			configuration, FIELD_FALLBACK, FALLBACK_EMPTY);

		if (!FALLBACK_RECENT.equals(fallback)) {
			return Collections.emptyList();
		}

		return _blogsEntryLocalService.getGroupEntries(
			serviceContext.getScopeGroupId(),
			new QueryDefinition<>(
				WorkflowConstants.STATUS_APPROVED, 0, _FALLBACK_LIMIT, null));
	}

	private String _getSingleValue(
		Map<String, String[]> configuration, String name, String defaultValue) {

		if (configuration == null) {
			return defaultValue;
		}

		String[] values = configuration.get(name);

		if ((values == null) || (values.length == 0)) {
			return defaultValue;
		}

		return GetterUtil.getString(values[0], defaultValue);
	}

	private List<UserGroup> _getUserGroups() {
		ServiceContext serviceContext =
			ServiceContextThreadLocal.getServiceContext();

		if (serviceContext == null) {
			// getConfigurationInfoForm takes no arguments, so the company can
			// only come from the thread local. If it is absent the form is
			// rendered empty rather than guessing at a company id.

			_log.error(
				"Unable to resolve a service context, so no user group " +
					"fields can be built");

			return Collections.emptyList();
		}

		return _userGroupLocalService.getUserGroups(
			serviceContext.getCompanyId());
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
	 * Skips entries that no longer resolve or are no longer approved, rather
	 * than surfacing a null into the fragment. A configuration naming a deleted
	 * entry should degrade to a shorter list, not to an error.
	 */
	private List<BlogsEntry> _resolve(Set<Long> entryIds) {
		List<BlogsEntry> blogsEntries = new ArrayList<>(entryIds.size());

		for (Long entryId : entryIds) {
			BlogsEntry blogsEntry = _blogsEntryLocalService.fetchBlogsEntry(
				entryId);

			if ((blogsEntry != null) &&
				(blogsEntry.getStatus() == WorkflowConstants.STATUS_APPROVED)) {

				blogsEntries.add(blogsEntry);
			}
		}

		return blogsEntries;
	}

	private static final int _FALLBACK_LIMIT = 20;

	private static final Log _log = LogFactoryUtil.getLog(
		UserGroupRecommendationsInfoCollectionProvider.class);

	@Reference
	private BlogsEntryLocalService _blogsEntryLocalService;

	@Reference
	private Language _language;

	@Reference
	private UserGroupLocalService _userGroupLocalService;

}
