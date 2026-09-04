package com.liferay.fragment.override.application;

import com.liferay.fragment.model.FragmentEntryLink;
import com.liferay.fragment.service.FragmentEntryLinkLocalService;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.Layout;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.security.permission.ActionKeys;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.service.LayoutLocalService;
import com.liferay.portal.kernel.service.permission.LayoutPermissionUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.portal.kernel.util.PropsUtil;

import java.util.Collections;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;

/**
 * JAX-RS application providing an authenticated, authorized PUT endpoint to update
 * FragmentEntryLink editableValues inside published Site Initializer pages.
 *
 * <p>
 * This works around upstream restriction LPD-99955 (where Headless Admin Site API
 * rejects specification updates on published site initializer pages).
 * Updates route through {@link FragmentEntryLinkLocalService}, ensuring automatic
 * cache invalidation, indexing, and model listener execution.
 * </p>
 *
 * <p>
 * Mutation is gated behind {@code feature.flag.LPD-99955=true} in
 * {@code portal-ext.properties} (with {@code feature.flag.LPS-178052=true}
 * supported as a backward-compatible alias), requires authentication, and enforces
 * update permissions against the target layout.
 * </p>
 *
 * @author peterrichards
 */
@Component(
	property = {
		JaxrsWhiteboardConstants.JAX_RS_APPLICATION_BASE + "=/fragment-override",
		JaxrsWhiteboardConstants.JAX_RS_NAME + "=FragmentOverride"
	},
	service = Application.class
)
public class FragmentOverrideApplication extends Application {

	@Override
	public Set<Object> getSingletons() {
		return Collections.<Object>singleton(this);
	}

	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	public Response status() {
		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		jsonObject.put("status", "active");
		jsonObject.put("featureFlag", "feature.flag.LPD-99955");
		jsonObject.put("enabled", isFeatureFlagEnabled());

		return Response.ok(jsonObject.toString(), MediaType.APPLICATION_JSON).build();
	}

	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/fragment-entry-links/{fragmentEntryLinkId}")
	@Produces(MediaType.APPLICATION_JSON)
	@PUT
	public Response updateFragmentEntryLink(
			@Context HttpServletRequest httpServletRequest,
			@PathParam("fragmentEntryLinkId") long fragmentEntryLinkId,
			String editableValues) {

		if (!isFeatureFlagEnabled()) {
			return _jsonError(
				Response.Status.FORBIDDEN, "FeatureDisabled",
				"Updating fragment entry links is disabled. Set feature.flag.LPD-99955=true in portal-ext.properties to enable.");
		}

		if (fragmentEntryLinkId <= 0 || editableValues == null || editableValues.trim().isEmpty()) {
			return _jsonError(
				Response.Status.BAD_REQUEST, "BadRequest",
				"Invalid fragmentEntryLinkId or empty editableValues payload.");
		}

		try {
			User user = PortalUtil.getUser(httpServletRequest);

			if (user == null || user.isDefaultUser()) {
				return _jsonError(
					Response.Status.UNAUTHORIZED, "Unauthorized",
					"Authentication is required to update fragment entry links.");
			}

			FragmentEntryLink fragmentEntryLink =
				_fragmentEntryLinkLocalService.fetchFragmentEntryLink(fragmentEntryLinkId);

			if (fragmentEntryLink == null) {
				return _jsonError(
					Response.Status.NOT_FOUND, "NotFound",
					"FragmentEntryLink with ID " + fragmentEntryLinkId + " does not exist.");
			}

			PermissionChecker permissionChecker =
				PermissionThreadLocal.getPermissionChecker();

			if (!_hasUpdatePermission(permissionChecker, user, fragmentEntryLink)) {
				return _jsonError(
					Response.Status.FORBIDDEN, "Forbidden",
					"User does not have permission to update this fragment entry link.");
			}

			_fragmentEntryLinkLocalService.updateFragmentEntryLink(
				user.getUserId(), fragmentEntryLinkId, editableValues, true);

			JSONObject responseJSON = JSONFactoryUtil.createJSONObject();

			responseJSON.put("status", "success");
			responseJSON.put("fragmentEntryLinkId", fragmentEntryLinkId);
			responseJSON.put("userId", user.getUserId());

			return Response.ok(responseJSON.toString(), MediaType.APPLICATION_JSON).build();
		}
		catch (Exception exception) {
			_log.error("Failed to update fragment entry link " + fragmentEntryLinkId, exception);

			return _jsonError(
				Response.Status.INTERNAL_SERVER_ERROR, "InternalServerError",
				"An unexpected error occurred while updating the fragment.");
		}
	}

	private boolean _hasUpdatePermission(
			PermissionChecker permissionChecker, User user,
			FragmentEntryLink fragmentEntryLink)
		throws Exception {

		if (permissionChecker == null) {
			return false;
		}

		if (permissionChecker.isOmniadmin() ||
			permissionChecker.isCompanyAdmin(user.getCompanyId()) ||
			permissionChecker.isGroupAdmin(fragmentEntryLink.getGroupId())) {

			return true;
		}

		Layout layout = _layoutLocalService.fetchLayout(fragmentEntryLink.getPlid());

		if (layout == null) {
			return false;
		}

		return LayoutPermissionUtil.contains(
			permissionChecker, layout, ActionKeys.UPDATE);
	}

	protected boolean isFeatureFlagEnabled() {
		return GetterUtil.getBoolean(PropsUtil.get("feature.flag.LPD-99955"))
			|| GetterUtil.getBoolean(PropsUtil.get("feature.flag.LPS-178052"));
	}

	private Response _jsonError(
		Response.Status status, String error, String message) {

		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		jsonObject.put("error", error);
		jsonObject.put("message", message);

		return Response.status(
			status
		).entity(
			jsonObject.toString()
		).type(
			MediaType.APPLICATION_JSON
		).build();
	}

	private static final Log _log = LogFactoryUtil.getLog(FragmentOverrideApplication.class);

	@Reference
	private FragmentEntryLinkLocalService _fragmentEntryLinkLocalService;

	@Reference
	private LayoutLocalService _layoutLocalService;

}

