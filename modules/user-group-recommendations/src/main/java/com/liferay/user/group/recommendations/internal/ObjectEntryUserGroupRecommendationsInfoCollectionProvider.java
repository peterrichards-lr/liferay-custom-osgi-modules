package com.liferay.user.group.recommendations.internal;

import com.liferay.info.collection.provider.CollectionQuery;
import com.liferay.info.collection.provider.SingleFormVariationInfoCollectionProvider;
import com.liferay.info.pagination.InfoPage;
import com.liferay.info.pagination.Pagination;
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

		return _objectEntryLocalService.getObjectEntries(
			serviceContext.getScopeGroupId(),
			_objectDefinition.getObjectDefinitionId(), 0, _FALLBACK_LIMIT);
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
	 * Resolves each reference as an object entry external reference code, then
	 * as a numeric entry id.
	 *
	 * <p>
	 * External reference codes are the portable form and the one to use: entry
	 * ids differ between environments, so a configuration written against one
	 * instance resolves to nothing -- or to unrelated entries -- on another.
	 * </p>
	 */
	private List<ObjectEntry> _resolve(
		Set<String> references, ServiceContext serviceContext) {

		List<ObjectEntry> objectEntries = new ArrayList<>(references.size());

		for (String reference : references) {
			ObjectEntry objectEntry = _objectEntryLocalService.fetchObjectEntry(
				reference, serviceContext.getCompanyId(),
				serviceContext.getScopeGroupId());

			if ((objectEntry == null) && Validator.isNumber(reference)) {
				objectEntry = _objectEntryLocalService.fetchObjectEntry(
					GetterUtil.getLong(reference));
			}

			if (objectEntry == null) {
				_log.error(
					"No " + _objectDefinition.getExternalReferenceCode() +
						" entry in group " + serviceContext.getScopeGroupId() +
							" matches the configured reference " + reference);

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
