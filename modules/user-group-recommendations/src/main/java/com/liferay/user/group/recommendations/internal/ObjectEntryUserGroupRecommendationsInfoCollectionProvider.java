package com.liferay.user.group.recommendations.internal;

import com.liferay.info.collection.provider.CollectionQuery;
import com.liferay.info.collection.provider.InfoCollectionProvider;
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
import com.liferay.user.group.recommendations.UserGroupRecommendationsInfoCollectionProvider;
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
	implements InfoCollectionProvider<ObjectEntry> {

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
				_configuredReference,
				CurrentUserUtil.getUserId(serviceContext),
				GetterUtil.getString(
					configuration.multiGroupStrategy(), "firstMatch"),
				_userGroupLocalService);

			List<ObjectEntry> objectEntries = _resolve(
				references, serviceContext);

			if (objectEntries.isEmpty()) {
				objectEntries = _getFallbackObjectEntries(
					configuration, serviceContext);
			}

			return InfoPageUtil.paginate(objectEntries, pagination);
		}
		catch (Exception exception) {
			_log.error(
				"Unable to resolve user group recommendations for " +
					_objectDefinition.getExternalReferenceCode(),
				exception);

			return InfoPage.of(Collections.emptyList(), pagination, 0);
		}
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

	/**
	 * What to serve when the user matches no configured group.
	 *
	 * <p>
	 * {@code random} exists so a page still looks alive for a visitor who is in
	 * no group -- a guest, or anyone outside the curated audiences -- rather
	 * than showing an empty block.
	 * </p>
	 */
	private List<ObjectEntry> _getFallbackObjectEntries(
		UserGroupRecommendationsConfiguration configuration,
		ServiceContext serviceContext) {

		String fallback = GetterUtil.getString(
			configuration.fallback(), UserGroupRecommendationsInfoCollectionProvider.FALLBACK_EMPTY);

		if (UserGroupRecommendationsInfoCollectionProvider.FALLBACK_EMPTY.equals(fallback)) {
			return Collections.emptyList();
		}

		int limit = configuration.fallbackLimit();

		if (limit <= 0) {
			limit = _DEFAULT_FALLBACK_LIMIT;
		}

		long[] groupIds = CurrentScopeUtil.getSearchableGroupIds(
			_objectDefinition, serviceContext);

		List<ObjectEntry> objectEntries = new ArrayList<>();

		for (long groupId : groupIds) {
			objectEntries.addAll(
				_objectEntryLocalService.getObjectEntries(
					groupId, _objectDefinition.getObjectDefinitionId(), 0,
					_FALLBACK_POOL));
		}

		if (objectEntries.isEmpty()) {
			_warnIfEntriesExistElsewhere(groupIds);
		}

		if (UserGroupRecommendationsInfoCollectionProvider.FALLBACK_RANDOM.equals(fallback)) {

			// Shuffle a copy: the service may hand back an immutable or
			// cached list, and reordering it in place would be a side effect
			// on someone else's data.

			objectEntries = new ArrayList<>(objectEntries);

			Collections.shuffle(objectEntries);
		}

		if (objectEntries.size() > limit) {
			return new ArrayList<>(objectEntries.subList(0, limit));
		}

		return objectEntries;
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

		long[] groupIds = CurrentScopeUtil.getSearchableGroupIds(_objectDefinition, serviceContext);

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

	/**
	 * Distinguishes "this content type has no entries" from "its entries are
	 * not in the groups we searched".
	 *
	 * <p>
	 * There is a count of entries by object definition that takes no group, but
	 * no matching list, so this cannot be used to serve content -- only to say
	 * plainly which of the two situations applies. That is the single most
	 * useful thing to know when a collection renders empty, and it was not
	 * knowable from the log before.
	 * </p>
	 */
	private void _warnIfEntriesExistElsewhere(long[] groupIds) {
		try {
			int total = _objectEntryLocalService.getObjectEntriesCount(
				_objectDefinition.getObjectDefinitionId());

			if (total > 0) {
				_log.warn(
					"Found no " + _configuredReference + " entries in groups " +
						Arrays.toString(groupIds) + ", but " + total +
							" exist for this content type. Its entries are " +
								"not reachable from this site -- if it is " +
									"depot scoped, connect the asset library " +
										"holding them.");
			}
			else if (_log.isInfoEnabled()) {
				_log.info(
					"Content type " + _configuredReference +
						" has no entries at all");
			}
		}
		catch (Exception exception) {
			_log.error("Unable to count entries", exception);
		}
	}

	private static final int _DEFAULT_FALLBACK_LIMIT = 3;

	private static final int _FALLBACK_POOL = 100;

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
