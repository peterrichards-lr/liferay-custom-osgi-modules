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
import com.liferay.portal.kernel.settings.FallbackKeysSettingsUtil;
import com.liferay.portal.kernel.settings.GroupServiceSettingsLocator;
import com.liferay.portal.kernel.settings.ModifiableSettings;
import com.liferay.portal.kernel.settings.Settings;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.PortalUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
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

			ModifiableSettings modifiableSettings = settings.getModifiableSettings();
			boolean configured = false;

			if (modifiableSettings != null && modifiableSettings.getModifiedKeys() != null) {
				configured = modifiableSettings.getModifiedKeys().contains("commerceSiteType");
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

			JSONObject responseJSON = JSONFactoryUtil.createJSONObject();

			responseJSON.put("channelId", channelId);
			responseJSON.put("siteType", siteType);
			responseJSON.put("siteTypeLabel", siteTypeLabel);
			responseJSON.put("siteTypeStatus", siteTypeStatus);

			JSONArray allowedAccountTypesJSONArray = JSONFactoryUtil.createJSONArray();
			for (String allowedAccountType : allowedAccountTypes) {
				allowedAccountTypesJSONArray.put(allowedAccountType);
			}
			responseJSON.put("allowedAccountTypes", allowedAccountTypesJSONArray);
			responseJSON.put("configured", configured);

			return Response.ok(responseJSON.toString(), MediaType.APPLICATION_JSON).build();
		}
		catch (Exception exception) {
			_log.error("Failed to retrieve site type for channelId " + channelId, exception);

			return _jsonError(
				Response.Status.INTERNAL_SERVER_ERROR, "InternalServerError",
				"An unexpected error occurred while retrieving commerce site type.");
		}
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

	private static final Log _log = LogFactoryUtil.getLog(CommerceSiteTypeResource.class);

}
