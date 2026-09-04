package com.liferay.search.reindex;

import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.search.IndexWriterHelperUtil;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.portal.kernel.util.Validator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class SearchReindexResource {

	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	public Response status() {
		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		jsonObject.put("status", "active");
		jsonObject.put("module", "search-reindex");

		return Response.ok(jsonObject.toString(), MediaType.APPLICATION_JSON).build();
	}

	@POST
	@Path("/reindex/all")
	@Produces(MediaType.APPLICATION_JSON)
	public Response reindexAll(@Context HttpServletRequest httpServletRequest) {
		try {
			Response authResponse = _checkAuthenticationAndAuthorization(httpServletRequest);

			if (authResponse != null) {
				return authResponse;
			}

			long[] companyIds = PortalUtil.getCompanyIds();

			for (long companyId : companyIds) {
				IndexWriterHelperUtil.reindex(0, "reindex", new long[]{companyId}, null);
			}

			JSONObject responseJSON = JSONFactoryUtil.createJSONObject();

			responseJSON.put("status", "success");
			responseJSON.put("message", "All indexes scheduled for reindexing");
			responseJSON.put("companyCount", companyIds.length);

			return Response.ok(responseJSON.toString(), MediaType.APPLICATION_JSON).build();
		}
		catch (Exception exception) {
			_log.error("Failed to schedule reindex for all indexes", exception);

			return _jsonError(
				Response.Status.INTERNAL_SERVER_ERROR, "InternalServerError",
				"An unexpected error occurred while scheduling reindex.");
		}
	}

	@POST
	@Path("/reindex/{className}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response reindexClass(
		@Context HttpServletRequest httpServletRequest,
		@PathParam("className") String className) {

		try {
			if (Validator.isNull(className) || Validator.isBlank(className)) {
				return _jsonError(
					Response.Status.BAD_REQUEST, "BadRequest",
					"Parameter 'className' cannot be null or empty.");
			}

			Response authResponse = _checkAuthenticationAndAuthorization(httpServletRequest);

			if (authResponse != null) {
				return authResponse;
			}

			long[] companyIds = PortalUtil.getCompanyIds();

			for (long companyId : companyIds) {
				IndexWriterHelperUtil.reindex(0, "reindex", new long[]{companyId}, className, null);
			}

			JSONObject responseJSON = JSONFactoryUtil.createJSONObject();

			responseJSON.put("status", "success");
			responseJSON.put("className", className);
			responseJSON.put("message", "Reindex scheduled for " + className);
			responseJSON.put("companyCount", companyIds.length);

			return Response.ok(responseJSON.toString(), MediaType.APPLICATION_JSON).build();
		}
		catch (Exception exception) {
			_log.error("Failed to schedule reindex for class " + className, exception);

			return _jsonError(
				Response.Status.INTERNAL_SERVER_ERROR, "InternalServerError",
				"An unexpected error occurred while scheduling reindex.");
		}
	}

	private Response _checkAuthenticationAndAuthorization(HttpServletRequest httpServletRequest)
		throws PortalException {
		User user = PortalUtil.getUser(httpServletRequest);

		if (user == null || user.isDefaultUser()) {
			return _jsonError(
				Response.Status.UNAUTHORIZED, "Unauthorized",
				"Authentication is required to trigger search reindexing.");
		}

		PermissionChecker permissionChecker = PermissionThreadLocal.getPermissionChecker();

		if (permissionChecker == null || !permissionChecker.isOmniadmin()) {
			return _jsonError(
				Response.Status.FORBIDDEN, "Forbidden",
				"Omniadmin permissions are required to trigger search reindexing.");
		}

		return null;
	}

	private Response _jsonError(Response.Status status, String error, String message) {
		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		jsonObject.put("error", error);
		jsonObject.put("message", message);

		return Response.status(status).entity(jsonObject.toString()).type(MediaType.APPLICATION_JSON).build();
	}

	private static final Log _log = LogFactoryUtil.getLog(SearchReindexResource.class);

}
