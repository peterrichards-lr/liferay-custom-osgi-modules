package com.liferay.user.group.recommendations.internal;

import com.liferay.info.collection.provider.InfoCollectionProvider;
import com.liferay.object.model.ObjectDefinition;
import com.liferay.object.service.ObjectDefinitionLocalService;
import com.liferay.object.service.ObjectEntryLocalService;
import com.liferay.portal.configuration.metatype.bnd.util.ConfigurableUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.Company;
import com.liferay.portal.kernel.service.CompanyLocalService;
import com.liferay.portal.kernel.service.UserGroupLocalService;
import com.liferay.portal.kernel.util.HashMapDictionaryBuilder;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.user.group.recommendations.configuration.UserGroupRecommendationsConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;

/**
 * Registers one collection provider per configured object definition, per
 * company.
 *
 * <p>
 * <b>Why this is not a declarative component.</b> An object-backed collection
 * provider must register under {@code item.class.name =
 * ObjectDefinition#getClassName()}, and for a custom object that class name
 * carries a short-name suffix generated when the definition is created --
 * {@code com.liferay.object.model.ObjectDefinition#Z7P5} and so on. It is
 * therefore instance-specific and cannot appear in an annotation. Liferay
 * registers its own object providers programmatically for exactly this reason;
 * see {@code ObjectDefinitionDeployerImpl}.
 * </p>
 *
 * <p>
 * <b>Lifecycle, and its limit.</b> Registration happens on activation and again
 * whenever the configuration changes, resolving each configured external
 * reference code against every company. A definition created <i>after</i> that
 * will not be picked up until the configuration is saved again or the bundle is
 * restarted. That is a deliberate simplification: tracking definition creation
 * would mean a model listener and a good deal more lifecycle to get wrong, and
 * the configuration naming a definition is normally written after the
 * definition exists.
 * </p>
 *
 * @author Peter Richards
 */
@Component(
	configurationPid = "com.liferay.user.group.recommendations.configuration.UserGroupRecommendationsConfiguration",
	service = {}
)
public class UserGroupRecommendationsObjectRegistrar {

	@Activate
	@Modified
	protected void activate(
		BundleContext bundleContext, Map<String, Object> properties) {

		_bundleContext = bundleContext;

		_configuration = ConfigurableUtil.createConfigurable(
			UserGroupRecommendationsConfiguration.class, properties);

		_unregisterAll();

		String[] externalReferenceCodes =
			_configuration.objectDefinitionExternalReferenceCodes();

		if ((externalReferenceCodes == null) ||
			(externalReferenceCodes.length == 0)) {

			return;
		}

		for (Company company : _companyLocalService.getCompanies()) {
			for (String externalReferenceCode : externalReferenceCodes) {
				if (Validator.isNull(externalReferenceCode)) {
					continue;
				}

				_register(company, externalReferenceCode.trim());
			}
		}
	}

	@Deactivate
	protected void deactivate() {
		_unregisterAll();
	}

	/**
	 * Resolves a configured value as an external reference code, then as an
	 * object definition name, then as a custom object definition name with the
	 * {@code C_} prefix Liferay adds.
	 *
	 * <p>
	 * The name forms matter for custom content types. A system definition has a
	 * legible {@code L_}-prefixed code, but a custom one created through the UI
	 * is given a generated external reference code, and pinning a configuration
	 * file to a generated identifier is both unreadable and not portable between
	 * environments. Accepting {@code MotorBlog} keeps the file legible.
	 * </p>
	 */
	private ObjectDefinition _fetchObjectDefinition(
		long companyId, String reference) {

		ObjectDefinition objectDefinition =
			_objectDefinitionLocalService.
				fetchObjectDefinitionByExternalReferenceCode(
					reference, companyId);

		if (objectDefinition != null) {
			return objectDefinition;
		}

		objectDefinition = _objectDefinitionLocalService.fetchObjectDefinition(
			companyId, reference);

		if (objectDefinition != null) {
			return objectDefinition;
		}

		if (reference.startsWith(_CUSTOM_NAME_PREFIX)) {
			return null;
		}

		return _objectDefinitionLocalService.fetchObjectDefinition(
			companyId, _CUSTOM_NAME_PREFIX + reference);
	}

	private void _register(Company company, String externalReferenceCode) {
		ObjectDefinition objectDefinition = _fetchObjectDefinition(
			company.getCompanyId(), externalReferenceCode);

		if (objectDefinition == null) {
			_log.error(
				"No object definition matches \"" + externalReferenceCode +
					"\" in company " + company.getCompanyId() +
						" as an external reference code, a name, or a name " +
							"prefixed " + _CUSTOM_NAME_PREFIX +
								", so no collection provider was registered");

			return;
		}

		// Mirrors the properties ObjectDefinitionDeployerImpl registers its own
		// object collection providers with. Both are required: item.class.name
		// is how the page editor finds a provider for this content type, and
		// company.id keeps one instance's definitions out of another's.

		_serviceRegistrations.add(
			_bundleContext.registerService(
				InfoCollectionProvider.class,
				new ObjectEntryUserGroupRecommendationsInfoCollectionProvider(
					() -> _configuration, externalReferenceCode,
					_configuration.label(), objectDefinition,
					_objectEntryLocalService,
					_userGroupLocalService),
				HashMapDictionaryBuilder.<String, Object>put(
					"company.id", company.getCompanyId()
				).put(
					"item.class.name", objectDefinition.getClassName()
				).build()));

		if (_log.isInfoEnabled()) {
			_log.info(
				"Registered a user group recommendations collection provider " +
					"for " + externalReferenceCode + " in company " +
						company.getCompanyId());
		}
	}

	private void _unregisterAll() {
		for (ServiceRegistration<?> serviceRegistration :
				_serviceRegistrations) {

			serviceRegistration.unregister();
		}

		_serviceRegistrations.clear();
	}

	private static final String _CUSTOM_NAME_PREFIX = "C_";

	private static final Log _log = LogFactoryUtil.getLog(
		UserGroupRecommendationsObjectRegistrar.class);

	private BundleContext _bundleContext;

	@Reference
	private CompanyLocalService _companyLocalService;

	private volatile UserGroupRecommendationsConfiguration _configuration;

	@Reference
	private ObjectDefinitionLocalService _objectDefinitionLocalService;

	@Reference
	private ObjectEntryLocalService _objectEntryLocalService;

	private final List<ServiceRegistration<?>> _serviceRegistrations =
		new ArrayList<>();

	@Reference
	private UserGroupLocalService _userGroupLocalService;

}
