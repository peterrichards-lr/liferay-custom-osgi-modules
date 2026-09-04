package com.liferay.fragment.override.application;

import com.liferay.fragment.model.FragmentEntryLink;
import com.liferay.fragment.service.FragmentEntryLinkLocalService;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.PropsUtil;

import java.util.Collections;
import java.util.Set;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;

/**
 * JAX-RS application providing a PUT endpoint to update FragmentEntryLink
 * editableValues inside published Site Initializer pages.
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
 * supported as a backward-compatible alias).
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
		boolean enabled = _isFeatureFlagEnabled();

		String json = "{\"status\":\"active\",\"featureFlag\":\"feature.flag.LPD-99955\",\"enabled\":"
			+ enabled + "}";

		return Response.ok(json, MediaType.APPLICATION_JSON).build();
	}

	@Consumes(MediaType.APPLICATION_JSON)
	@Path("/fragment-entry-links/{fragmentEntryLinkId}")
	@Produces(MediaType.APPLICATION_JSON)
	@PUT
	public Response updateFragmentEntryLink(
			@PathParam("fragmentEntryLinkId") long fragmentEntryLinkId,
			String editableValues) {

		if (!_isFeatureFlagEnabled()) {
			return Response.status(Response.Status.FORBIDDEN).entity(
				"{\"error\":\"FeatureDisabled\",\"message\":\"Updating fragment entry links is disabled. Set feature.flag.LPD-99955=true in portal-ext.properties to enable.\"}"
			).build();
		}

		if (fragmentEntryLinkId <= 0 || editableValues == null || editableValues.trim().isEmpty()) {
			return Response.status(Response.Status.BAD_REQUEST).entity(
				"{\"error\":\"BadRequest\",\"message\":\"Invalid fragmentEntryLinkId or empty editableValues payload.\"}"
			).build();
		}

		try {
			FragmentEntryLink fragmentEntryLink =
				_fragmentEntryLinkLocalService.fetchFragmentEntryLink(fragmentEntryLinkId);

			if (fragmentEntryLink == null) {
				return Response.status(Response.Status.NOT_FOUND).entity(
					"{\"error\":\"NotFound\",\"message\":\"FragmentEntryLink with ID "
						+ fragmentEntryLinkId + " does not exist.\"}"
				).build();
			}

			_fragmentEntryLinkLocalService.updateFragmentEntryLink(
				fragmentEntryLink.getUserId(), fragmentEntryLinkId, editableValues, true);

			return Response.ok(
				"{\"status\":\"success\",\"fragmentEntryLinkId\":" + fragmentEntryLinkId + "}"
			).build();
		}
		catch (Exception exception) {
			_log.error("Failed to update fragment entry link " + fragmentEntryLinkId, exception);

			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
				"{\"error\":\"InternalServerError\",\"message\":\""
					+ exception.getMessage() + "\"}"
			).build();
		}
	}

	private boolean _isFeatureFlagEnabled() {
		return GetterUtil.getBoolean(PropsUtil.get("feature.flag.LPD-99955"))
			|| GetterUtil.getBoolean(PropsUtil.get("feature.flag.LPS-178052"));
	}

	private static final Log _log = LogFactoryUtil.getLog(FragmentOverrideApplication.class);

	@Reference
	private FragmentEntryLinkLocalService _fragmentEntryLinkLocalService;

}
