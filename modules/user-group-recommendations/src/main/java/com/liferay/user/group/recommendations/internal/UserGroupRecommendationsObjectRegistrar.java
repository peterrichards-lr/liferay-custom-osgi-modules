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
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.user.group.recommendations.configuration.UserGroupRecommendationsConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
// No @Designate here: both components share one configuration PID, and bnd
// rejects a duplicate designate for it. The declarative provider carries it.
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

			// Say so. This is the likeliest misconfiguration -- the bundle
			// deployed but its .config did not -- and returning quietly made
			// it indistinguishable from a failed lookup or an unsatisfied
			// component, which cost real time to tell apart.

			_log.info(
				"No object definitions are configured, so no collection " +
					"providers were registered. Set " +
						"objectDefinitionExternalReferenceCodes to serve new " +
							"CMS content.");

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
	 * Resolves a configured value to an object definition, accepting any of the
	 * identifiers a person might reasonably write.
	 *
	 * <p>
	 * In order: external reference code, exact name, the {@code C_}-prefixed
	 * name Liferay gives custom definitions, and finally a case-insensitive
	 * comparison against name, short name and every localised label.
	 * </p>
	 *
	 * <p>
	 * The label matters most in practice and was the omission that broke the
	 * first deployment. Liferay derives a definition's name from its label with
	 * its own capitalisation -- a definition labelled {@code MotorBlog} is named
	 * {@code Motorblog} -- and the label is the only one of the two an
	 * administrator ever sees. A generated external reference code is no help
	 * either, being a UUID. Matching the label is what makes a configuration
	 * file writable from what is on screen.
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

		if (!reference.startsWith(_CUSTOM_NAME_PREFIX)) {
			objectDefinition =
				_objectDefinitionLocalService.fetchObjectDefinition(
					companyId, _CUSTOM_NAME_PREFIX + reference);

			if (objectDefinition != null) {
				return objectDefinition;
			}
		}

		// STATUS_APPROVED, not QueryUtil.ALL_POS. This is a persistence finder
		// doing an exact match on the status column, so the -1 that means "any"
		// elsewhere matches no row at all -- an approved definition has status
		// 0. Passing it left this scan iterating an empty list, so the label
		// and case-insensitive matching below silently never ran.

		for (ObjectDefinition candidate :
				_objectDefinitionLocalService.getObjectDefinitions(
					companyId, true, WorkflowConstants.STATUS_APPROVED)) {

			if (_matches(candidate, reference)) {
				return candidate;
			}
		}

		return null;
	}

	private boolean _matches(ObjectDefinition objectDefinition, String reference) {
		if (StringUtil.equalsIgnoreCase(
				objectDefinition.getName(), reference) ||
			StringUtil.equalsIgnoreCase(
				objectDefinition.getName(), _CUSTOM_NAME_PREFIX + reference) ||
			StringUtil.equalsIgnoreCase(
				objectDefinition.getShortName(), reference)) {

			return true;
		}

		Map<Locale, String> labelMap = objectDefinition.getLabelMap();

		if (labelMap == null) {
			return false;
		}

		for (String label : labelMap.values()) {
			if (StringUtil.equalsIgnoreCase(label, reference)) {
				return true;
			}
		}

		return false;
	}

	private void _register(Company company, String externalReferenceCode) {
		ObjectDefinition objectDefinition = _fetchObjectDefinition(
			company.getCompanyId(), externalReferenceCode);

		if (objectDefinition == null) {
			_log.error(
				"No object definition matches \"" + externalReferenceCode +
					"\" in company " + company.getCompanyId() +
						" as an external reference code, a name, a name " +
							"prefixed " + _CUSTOM_NAME_PREFIX +
								", a short name, or a label, so no collection " +
									"provider was registered");

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

		{
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
