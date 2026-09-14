package com.liferay.user.group.recommendations.internal;

import com.liferay.depot.util.SiteConnectedGroupGroupProviderUtil;
import com.liferay.info.collection.provider.CollectionQuery;
import com.liferay.info.collection.provider.SingleFormVariationInfoCollectionProvider;
import com.liferay.info.pagination.InfoPage;
import com.liferay.info.pagination.Pagination;
import com.liferay.object.constants.ObjectDefinitionConstants;
import com.liferay.object.model.ObjectDefinition;
import com.liferay.object.model.ObjectEntry;
import com.liferay.object.service.ObjectEntryLocalService;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceContextThreadLocal;
import com.liferay.portal.kernel.service.UserGroupLocalService;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.user.group.recommendations.configuration.UserGroupRecommendationsConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Serves a curated set of object entries -- new CMS content -- chosen per user
 * group.
 *
 * <p>
 * This is <b>not</b> an OSGi component. Collection providers for object-backed
 * content cannot be declared statically: the {@code item.class.name} they must
 * register under is {@code ObjectDefinition#getClassName()}, which carries a
 * short-name suffix generated when the definition is created and therefore
 * differs between instances. Liferay registers its own object collection
 * providers programmatically for the same reason -- see
 * {@code ObjectDefinitionDeployerImpl} -- and
 * {@link UserGroupRecommendationsObjectRegistrar} does the same here, one
 * instance per definition per company.
 * </p>
 *
 * @author Peter Richards
 */
public class ObjectEntryUserGroupRecommendationsInfoCollectionProvider
	implements SingleFormVariationInfoCollectionProvider<ObjectEntry> {

	public ObjectEntryUserGroupRecommendationsInfoCollectionProvider(
		Supplier<UserGroupRecommendationsConfiguration> configurationSupplier,
		String configuredReference, String label,
		ObjectDefinition objectDefinition,
		ObjectEntryLocalService objectEntryLocalService,
		UserGroupLocalService userGroupLocalService) {

		_configurationSupplier = configurationSupplier;
		_configuredReference = configuredReference;
		_label = label;
		_objectDefinition = objectDefinition;
		_objectEntryLocalService = objectEntryLocalService;
		_userGroupLocalService = userGroupLocalService;
	}

	@Override
	public InfoPage<ObjectEntry> getCollectionInfoPage(
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

			UserGroupRecommendationsConfiguration configuration =
				_configurationSupplier.get();

			Set<String> references = UserGroupMapping.getReferences(
				configuration.objectUserGroupEntries(),
				_configuredReference, serviceContext.getUserId(),
				GetterUtil.getString(
					configuration.multiGroupStrategy(), "firstMatch"),
				_userGroupLocalService);

			List<ObjectEntry> objectEntries = _resolve(
				references, serviceContext);

			if (objectEntries.isEmpty()) {
				objectEntries = _getFallbackObjectEntries(
					configuration, serviceContext);
			}

			return _paginate(objectEntries, pagination);
		}
		catch (Exception exception) {
			_log.error(
				"Unable to resolve user group recommendations for " +
					_objectDefinition.getExternalReferenceCode(),
				exception);

			return InfoPage.of(Collections.emptyList(), pagination, 0);
		}
	}

	/**
	 * Scopes the provider to this object definition, so the page editor offers
	 * it only where that content type is in play.
	 */
	@Override
	public String getFormVariationKey() {
		return String.valueOf(_objectDefinition.getObjectDefinitionId());
	}

	@Override
	public String getKey() {
		return "userGroupRecommendations#" +
			_objectDefinition.getExternalReferenceCode();
	}

	@Override
	public String getLabel(Locale locale) {
		return _label;
	}

	private List<ObjectEntry> _getFallbackObjectEntries(
		UserGroupRecommendationsConfiguration configuration,
		ServiceContext serviceContext) {

		if (!Objects.equals(
				GetterUtil.getString(configuration.fallback(), "empty"),
				"recent")) {

			return Collections.emptyList();
		}

		List<ObjectEntry> objectEntries = new ArrayList<>();

		for (long groupId : _getGroupIds(serviceContext)) {
			objectEntries.addAll(
				_objectEntryLocalService.getObjectEntries(
					groupId, _objectDefinition.getObjectDefinitionId(), 0,
					_FALLBACK_LIMIT));

			if (objectEntries.size() >= _FALLBACK_LIMIT) {
				break;
			}
		}

		return objectEntries;
	}

	/**
	 * The groups an entry of this definition could live in.
	 *
	 * <p>
	 * A definition scoped to {@code depot} stores its entries in an Asset
	 * Library, not in the site the page belongs to, so looking only in
	 * {@code getScopeGroupId} finds nothing. The CMS content structures are
	 * depot-scoped by default, which makes this the normal case rather than an
	 * edge one. Liferay's own object collection provider consults the same
	 * helper for the same reason.
	 * </p>
	 */
	private long[] _getGroupIds(ServiceContext serviceContext) {
		long scopeGroupId = serviceContext.getScopeGroupId();

		if (!Objects.equals(
				_objectDefinition.getScope(),
				ObjectDefinitionConstants.SCOPE_DEPOT)) {

			return new long[] {scopeGroupId};
		}

		try {
			return SiteConnectedGroupGroupProviderUtil.
				getCurrentAndAncestorSiteAndDepotGroupIds(scopeGroupId);
		}
		catch (Exception exception) {
			_log.error(
				"Unable to resolve the asset libraries connected to group " +
					scopeGroupId,
				exception);

			return new long[] {scopeGroupId};
		}
	}

	private InfoPage<ObjectEntry> _paginate(
		List<ObjectEntry> objectEntries, Pagination pagination) {

		int totalCount = objectEntries.size();

		int start = Math.min(pagination.getStart(), totalCount);
		int end = Math.min(pagination.getEnd(), totalCount);

		if (start > end) {
			start = end;
		}

		return InfoPage.of(
			objectEntries.subList(start, end), pagination, totalCount);
	}

	/**
	 * Resolves each reference as an external reference code, then as a friendly
	 * URL, then as a numeric entry id.
	 *
	 * <p>
	 * Prefer the friendly URL. An object entry created through the UI is given a
	 * <i>generated</i> external reference code -- a UUID -- which is neither
	 * readable in a configuration file nor recognisable when reviewing one,
	 * while the friendly URL is the last segment of the entry's {@code /w/} URL
	 * and is chosen by whoever wrote the content. Entry ids are accepted last
	 * and are a convenience for a single environment only: they differ between
	 * instances, so a file using them resolves to nothing -- or to unrelated
	 * entries -- elsewhere.
	 * </p>
	 */
	private List<ObjectEntry> _resolve(
		Set<String> references, ServiceContext serviceContext) {

		long[] groupIds = _getGroupIds(serviceContext);

		List<ObjectEntry> objectEntries = new ArrayList<>(references.size());

		for (String reference : references) {
			ObjectEntry objectEntry = null;

			for (long groupId : groupIds) {
				objectEntry = _objectEntryLocalService.fetchObjectEntry(
					reference, groupId,
					_objectDefinition.getObjectDefinitionId());

				if (objectEntry == null) {

					// The friendly URL is the readable, portable form: it is
					// the last segment of the entry's /w/ URL, and unlike a
					// generated external reference code it can be written into
					// a configuration file by hand and recognised later.

					objectEntry = _objectEntryLocalService.fetchObjectEntry(
						groupId, _objectDefinition, reference);
				}

				if (objectEntry != null) {
					break;
				}
			}

			if ((objectEntry == null) && Validator.isNumber(reference)) {
				objectEntry = _objectEntryLocalService.fetchObjectEntry(
					GetterUtil.getLong(reference));
			}

			if (objectEntry == null) {
				_log.error(
					"No " + _configuredReference + " entry in groups " +
						Arrays.toString(groupIds) + " matches \"" + reference +
							"\" as an external reference code, a friendly " +
								"URL, or an entry id");

				continue;
			}

			objectEntries.add(objectEntry);
		}

		return objectEntries;
	}

	private static final int _FALLBACK_LIMIT = 20;

	private static final Log _log = LogFactoryUtil.getLog(
		ObjectEntryUserGroupRecommendationsInfoCollectionProvider.class);

	private final Supplier<UserGroupRecommendationsConfiguration>
		_configurationSupplier;
	private final String _configuredReference;
	private final String _label;
	private final ObjectDefinition _objectDefinition;
	private final ObjectEntryLocalService _objectEntryLocalService;
	private final UserGroupLocalService _userGroupLocalService;

}
