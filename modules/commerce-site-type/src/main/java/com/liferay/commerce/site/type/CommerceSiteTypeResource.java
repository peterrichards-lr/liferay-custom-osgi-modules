package com.liferay.commerce.site.type;

import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.security.permission.ActionKeys;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.service.ClassNameLocalServiceUtil;
import com.liferay.portal.kernel.service.GroupLocalServiceUtil;
import com.liferay.portal.kernel.settings.CompanyServiceSettingsLocator;
import com.liferay.portal.kernel.settings.FallbackKeysSettingsUtil;
import com.liferay.portal.kernel.settings.GroupServiceSettingsLocator;
import com.liferay.portal.kernel.settings.ModifiableSettings;
import com.liferay.portal.kernel.settings.Settings;
import com.liferay.portal.kernel.settings.SettingsException;
import com.liferay.portal.kernel.settings.SettingsLocator;
import com.liferay.portal.kernel.settings.SystemSettingsLocator;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.PortalUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class CommerceSiteTypeResource {

	/**
	 * Unauthenticated deployment liveness/readiness probe.
	 * Confirms the bundle is active and whiteboard endpoint is mounted.
	 */
	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	public Response status() {
		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		jsonObject.put("status", "active");
		jsonObject.put("module", "commerce-site-type");

		return Response.ok(jsonObject.toString(), MediaType.APPLICATION_JSON).build();
	}

	@GET
	@Path("/channels/{channelId}/site-type")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSiteType(
		@Context HttpServletRequest httpServletRequest,
		@PathParam("channelId") long channelId) {

		try {
			if (channelId <= 0) {
				return _jsonError(
					Response.Status.BAD_REQUEST, "BadRequest",
					"Parameter 'channelId' must be a positive integer.");
			}

			Response authResponse = _checkAuthentication(httpServletRequest);

			if (authResponse != null) {
				return authResponse;
			}

			long companyId = PortalUtil.getCompanyId(httpServletRequest);
			long classNameId = ClassNameLocalServiceUtil.getClassNameId(
				"com.liferay.commerce.product.model.CommerceChannel");

			Group group = GroupLocalServiceUtil.fetchGroup(companyId, classNameId, channelId);

			if (group == null) {
				return _jsonError(
					Response.Status.NOT_FOUND, "NotFound",
					"Commerce channel group not found for channelId " + channelId + ".");
			}

			Response permissionResponse = _checkAuthorization(httpServletRequest, companyId, group, channelId);

			if (permissionResponse != null) {
				return permissionResponse;
			}

			JSONObject responseJSON = _buildSiteTypePayload(
				channelId, _resolveSiteTypeState(companyId, group));

			return Response.ok(responseJSON.toString(), MediaType.APPLICATION_JSON).build();
		}
		catch (Exception exception) {
			_log.error("Failed to retrieve site type for channelId " + channelId, exception);

			return _jsonError(
				Response.Status.INTERNAL_SERVER_ERROR, "InternalServerError",
				"An unexpected error occurred while retrieving commerce site type.");
		}
	}

	/**
	 * Sets a channel's commerce site type.
	 *
	 * <p>
	 * Body: <code>{"siteType": 0|1|2}</code> for B2C, B2B and B2X respectively.
	 * </p>
	 *
	 * <p>
	 * The response is the same shape the matching <code>@GET</code> returns,
	 * read back after the write rather than echoed from the request, so a
	 * caller can see what actually took effect. <code>configuredScope</code> is
	 * the field worth checking: it reads <code>GROUP</code> only if the value
	 * was found explicitly set at group scope, so anything else means the write
	 * did not land where it was aimed.
	 * </p>
	 */
	@PUT
	@Path("/channels/{channelId}/site-type")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response setSiteType(
		@Context HttpServletRequest httpServletRequest,
		@PathParam("channelId") long channelId, String body) {

		try {
			if (channelId <= 0) {
				return _jsonError(
					Response.Status.BAD_REQUEST, "BadRequest",
					"Parameter 'channelId' must be a positive integer.");
			}

			Response authResponse = _checkAuthentication(httpServletRequest);

			if (authResponse != null) {
				return authResponse;
			}

			if ((body == null) || body.trim().isEmpty()) {
				return _jsonError(
					Response.Status.BAD_REQUEST, "BadRequest",
					"A JSON body of the form {\"siteType\": 0|1|2} is required.");
			}

			JSONObject requestJSON;

			try {
				requestJSON = JSONFactoryUtil.createJSONObject(body);
			}
			catch (Exception exception) {
				if (_log.isDebugEnabled()) {
					_log.debug("Unparseable request body", exception);
				}

				return _jsonError(
					Response.Status.BAD_REQUEST, "BadRequest",
					"Request body is not valid JSON.");
			}

			if ((requestJSON == null) || !requestJSON.has("siteType")) {
				return _jsonError(
					Response.Status.BAD_REQUEST, "BadRequest",
					"Request body must contain 'siteType'.");
			}

			// -1 rather than 0 as the sentinel: 0 is B2C, a value a caller
			// legitimately sends, so it cannot double as "absent or
			// unparseable".
			int siteType = requestJSON.getInt("siteType", -1);

			if ((siteType < 0) || (siteType > 2)) {
				return _jsonError(
					Response.Status.BAD_REQUEST, "BadRequest",
					"Parameter 'siteType' must be 0 (B2C), 1 (B2B) or 2 (B2X).");
			}

			long companyId = PortalUtil.getCompanyId(httpServletRequest);
			long classNameId = ClassNameLocalServiceUtil.getClassNameId(
				"com.liferay.commerce.product.model.CommerceChannel");

			Group group = GroupLocalServiceUtil.fetchGroup(companyId, classNameId, channelId);

			if (group == null) {
				return _jsonError(
					Response.Status.NOT_FOUND, "NotFound",
					"Commerce channel group not found for channelId " + channelId + ".");
			}

			Response permissionResponse = _checkUpdateAuthorization(companyId, group, channelId);

			if (permissionResponse != null) {
				return permissionResponse;
			}

			GroupServiceSettingsLocator locator = new GroupServiceSettingsLocator(
				group.getGroupId(), "com.liferay.commerce.account");

			// Deliberately NOT FallbackKeysSettingsUtil here, unlike the read.
			// Fallback keys exist so a missing value resolves to a broader
			// scope; writing through that wrapper risks storing at a scope
			// other than the group this endpoint names. The write must land on
			// the group's own store or fail visibly.
			Settings settings = locator.getSettings();

			if (settings == null) {
				return _jsonError(
					Response.Status.INTERNAL_SERVER_ERROR, "InternalServerError",
					"No settings store resolved for channel " + channelId + ".");
			}

			ModifiableSettings modifiableSettings = settings.getModifiableSettings();

			if (modifiableSettings == null) {
				return _jsonError(
					Response.Status.INTERNAL_SERVER_ERROR, "InternalServerError",
					"Settings for channel " + channelId + " are not modifiable.");
			}

			modifiableSettings.setValue("commerceSiteType", String.valueOf(siteType));
			modifiableSettings.store();

			// Read back through a fresh locator rather than reporting what was
			// just written. Liferay's own UI is known to need a change-save-
			// change-back-save cycle before a channel's site type sticks, which
			// suggests the UI does more than write this key. Until that is
			// understood, the honest thing is to report what the store answers
			// afterwards and let the caller compare.
			_SiteTypeState state = _resolveSiteTypeState(companyId, group);

			if ((state.siteType != siteType) || !"GROUP".equals(state.configuredScope)) {
				_log.warn(
					"Wrote commerceSiteType=" + siteType + " to channel " + channelId +
						" but the store answers siteType=" + state.siteType +
							" at scope " + state.configuredScope);
			}

			JSONObject responseJSON = _buildSiteTypePayload(channelId, state);

			return Response.ok(responseJSON.toString(), MediaType.APPLICATION_JSON).build();
		}
		catch (Exception exception) {
			_log.error("Failed to set site type for channelId " + channelId, exception);

			return _jsonError(
				Response.Status.INTERNAL_SERVER_ERROR, "InternalServerError",
				"An unexpected error occurred while setting commerce site type.");
		}
	}

	/**
	 * Renders the response body shared by the read and the write, so the two
	 * can never describe the same channel differently.
	 */
	private JSONObject _buildSiteTypePayload(long channelId, _SiteTypeState state) {
		JSONObject responseJSON = JSONFactoryUtil.createJSONObject();

		responseJSON.put("channelId", channelId);
		responseJSON.put("siteType", state.siteType);
		responseJSON.put("siteTypeLabel", state.siteTypeLabel);
		responseJSON.put("siteTypeStatus", state.siteTypeStatus);
		responseJSON.put("configuredScope", state.configuredScope);

		JSONArray allowedAccountTypesJSONArray = JSONFactoryUtil.createJSONArray();
		for (String allowedAccountType : state.allowedAccountTypes) {
			allowedAccountTypesJSONArray.put(allowedAccountType);
		}
		responseJSON.put("allowedAccountTypes", allowedAccountTypesJSONArray);
		responseJSON.put("configured", state.configured);

		return responseJSON;
	}

	/**
	 * Reads the channel's site type and the scope it was set at. Kept separate
	 * from rendering so the write path can inspect what actually landed without
	 * parsing its own response back out of JSON.
	 */
	private _SiteTypeState _resolveSiteTypeState(long companyId, Group group)
		throws SettingsException {

		String configuredScope = "NONE";

		if (_isModifiedAt(new GroupServiceSettingsLocator(group.getGroupId(), "com.liferay.commerce.account"), "commerceSiteType")) {
			configuredScope = "GROUP";
		}
		else if (_isModifiedAt(new CompanyServiceSettingsLocator(companyId, "com.liferay.commerce.account"), "commerceSiteType")) {
			configuredScope = "COMPANY";
		}
		else if (_isModifiedAt(new SystemSettingsLocator("com.liferay.commerce.configuration.CommerceAccountGroupServiceConfiguration"), "commerceSiteType")) {
			configuredScope = "SYSTEM";
		}

		boolean configured = !configuredScope.equals("NONE");

		GroupServiceSettingsLocator locator = new GroupServiceSettingsLocator(
			group.getGroupId(), "com.liferay.commerce.account");

		Settings settings;

		try {
			settings = FallbackKeysSettingsUtil.getSettings(locator);
		}
		catch (Exception | LinkageError exception) {
			if (_log.isDebugEnabled()) {
				_log.debug("FallbackKeysSettingsUtil failed; falling back to locator.getSettings()", exception);
			}
			settings = locator.getSettings();
		}

		int siteType = GetterUtil.getInteger(settings.getValue("commerceSiteType", "0"), 0);

		String siteTypeLabel;
		String siteTypeStatus;
		String[] allowedAccountTypes;

		if (!configured) {
			siteTypeStatus = "NOT_CONFIGURED";
			siteTypeLabel = (siteType == 0) ? "B2C" : "UNKNOWN";
			allowedAccountTypes = new String[0];
		}
		else if (siteType == 0) {
			siteTypeStatus = "CONFIGURED";
			siteTypeLabel = "B2C";
			allowedAccountTypes = new String[]{"person"};
		}
		else if (siteType == 1) {
			siteTypeStatus = "CONFIGURED";
			siteTypeLabel = "B2B";
			allowedAccountTypes = new String[]{"business", "supplier"};
		}
		else if (siteType == 2) {
			siteTypeStatus = "CONFIGURED";
			siteTypeLabel = "B2X";
			allowedAccountTypes = new String[]{"business", "person", "supplier"};
		}
		else {
			siteTypeStatus = "UNRECOGNISED";
			siteTypeLabel = "UNKNOWN";
			allowedAccountTypes = new String[0];
		}

		return new _SiteTypeState(
			siteType, siteTypeLabel, siteTypeStatus, configuredScope, configured,
			allowedAccountTypes);
	}

	/**
	 * What the settings store says about one channel, before it becomes JSON.
	 */
	private static final class _SiteTypeState {

		private _SiteTypeState(
			int siteType, String siteTypeLabel, String siteTypeStatus,
			String configuredScope, boolean configured, String[] allowedAccountTypes) {

			this.siteType = siteType;
			this.siteTypeLabel = siteTypeLabel;
			this.siteTypeStatus = siteTypeStatus;
			this.configuredScope = configuredScope;
			this.configured = configured;
			this.allowedAccountTypes = allowedAccountTypes;
		}

		private final String[] allowedAccountTypes;
		private final boolean configured;
		private final String configuredScope;
		private final int siteType;
		private final String siteTypeLabel;
		private final String siteTypeStatus;

	}

	/**
	 * Writing requires UPDATE, not the VIEW the read settles for. A channel's
	 * site type decides which account types can order in it, so a caller able
	 * to read it is not thereby entitled to change it.
	 */
	private Response _checkUpdateAuthorization(long companyId, Group group, long channelId) {
		PermissionChecker permissionChecker = PermissionThreadLocal.getPermissionChecker();

		if (permissionChecker != null &&
			(permissionChecker.isOmniadmin() ||
			 permissionChecker.isCompanyAdmin(companyId) ||
			 permissionChecker.hasPermission(group.getGroupId(), "com.liferay.commerce.product.model.CommerceChannel", channelId, ActionKeys.UPDATE) ||
			 permissionChecker.hasPermission(group.getGroupId(), Group.class.getName(), group.getGroupId(), ActionKeys.UPDATE))) {
			return null;
		}

		return _jsonError(
			Response.Status.FORBIDDEN, "Forbidden",
			"Omniadmin, company admin, or channel/group UPDATE permissions are required to set commerce site type.");
	}

	private Response _checkAuthentication(HttpServletRequest httpServletRequest)
		throws PortalException {

		User user = PortalUtil.getUser(httpServletRequest);

		if (user == null || user.isDefaultUser()) {
			return _jsonError(
				Response.Status.UNAUTHORIZED, "Unauthorized",
				"Authentication is required to access commerce site type.");
		}

		return null;
	}

	private Response _checkAuthorization(
			HttpServletRequest httpServletRequest, long companyId, Group group, long channelId) {

		PermissionChecker permissionChecker = PermissionThreadLocal.getPermissionChecker();

		if (permissionChecker != null &&
			(permissionChecker.isOmniadmin() ||
			 permissionChecker.isCompanyAdmin(companyId) ||
			 permissionChecker.hasPermission(group.getGroupId(), "com.liferay.commerce.product.model.CommerceChannel", channelId, ActionKeys.VIEW) ||
			 permissionChecker.hasPermission(group.getGroupId(), Group.class.getName(), group.getGroupId(), ActionKeys.VIEW))) {
			return null;
		}

		return _jsonError(
			Response.Status.FORBIDDEN, "Forbidden",
			"Omniadmin, company admin, or channel/group VIEW permissions are required to access commerce site type.");
	}

	private Response _jsonError(Response.Status status, String error, String message) {
		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		jsonObject.put("error", error);
		jsonObject.put("message", message);

		return Response.status(status).entity(jsonObject.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	private boolean _isModifiedAt(SettingsLocator locator, String key) {
		try {
			Settings settings;

			try {
				settings = FallbackKeysSettingsUtil.getSettings(locator);
			}
			catch (Exception | LinkageError exception) {
				settings = locator.getSettings();
			}

			if (settings != null) {
				ModifiableSettings modifiableSettings = settings.getModifiableSettings();

				if (modifiableSettings != null && modifiableSettings.getModifiedKeys() != null) {
					return modifiableSettings.getModifiedKeys().contains(key);
				}
			}
		}
		catch (Exception exception) {
			if (_log.isDebugEnabled()) {
				_log.debug("Failed to check modified keys for locator " + locator.getSettingsId(), exception);
			}
		}

		return false;
	}

	private static final Log _log = LogFactoryUtil.getLog(CommerceSiteTypeResource.class);

}
