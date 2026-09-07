package com.liferay.client.extension.entry;

import com.liferay.client.extension.constants.ClientExtensionConstants;
import com.liferay.client.extension.constants.ClientExtensionEntryConstants;
import com.liferay.client.extension.model.ClientExtensionEntry;
import com.liferay.client.extension.service.ClientExtensionEntryLocalService;
import com.liferay.client.extension.type.CET;
import com.liferay.client.extension.type.CustomElementCET;
import com.liferay.client.extension.type.IFrameCET;
import com.liferay.client.extension.type.manager.CETManager;
import com.liferay.client.extension.util.CETUtil;
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
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.vulcan.pagination.Pagination;

import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Exposes the portlet id Liferay composes for a client extension, which no
 * Liferay API outside the portal JVM publishes.
 *
 * <p>
 * What was ruled out first, since a bundle is the expensive answer:
 * </p>
 *
 * <ul>
 * <li>GraphQL's <code>ClientExtension</code> type carries only
 * <code>clientExtensionConfig</code> and <code>externalReferenceCode</code>,
 * and there is no top-level query for entries.</li>
 * <li>No headless REST API covers client extension entries.</li>
 * <li>The portlet id is not stored anywhere a caller can read; it is assembled
 * at deploy time.</li>
 * </ul>
 *
 * <p>
 * <strong>The number embedded in the portlet id is the company id, not the
 * client extension entry id.</strong> This is worth stating plainly because it
 * is easy to assume otherwise, and both values are large counter-issued longs
 * that look alike. Liferay composes the id in
 * <code>CETDeployerImpl#_getPortletId</code> as
 * </p>
 *
 * <pre>
 * "com_liferay_client_extension_web_internal_portlet_" +
 *     "ClientExtensionEntryPortlet_" + cet.getCompanyId() + "_" +
 *         CETUtil.normalizeExternalReferenceCodeForPortletId(
 *             cet.getExternalReferenceCode())
 * </pre>
 *
 * <p>
 * That was verified by decompiling
 * <code>com.liferay.client.extension.web 1.0.94</code>, the artifact this
 * workspace's pinned <code>dxp-2026.q1.12-lts</code> ships, rather than read
 * from the master branch alone. The company id segment was introduced by
 * <code>client-extension-web</code> upgrade step <code>v3_0_1</code>
 * (<code>UpgradePortletId</code>), which renamed
 * <code>prefix + externalReferenceCode</code> to
 * <code>prefix + companyId + "_" + externalReferenceCode</code>; a hardcoded id
 * therefore breaks on any instance with a different company id, which is every
 * fresh database.
 * </p>
 *
 * <p>
 * The external reference code is normalised through {@link CETUtil}, Liferay's
 * own helper, rather than reproduced here. It is a bare
 * <code>replaceAll("\\W", "_")</code>, so an <code>LXC_</code> prefix in a
 * portlet id comes from the deployed external reference code itself, not from
 * any prefixing this bundle or Liferay performs.
 * </p>
 *
 * <p>
 * Only <code>customElement</code> and <code>iframe</code> extensions register a
 * portlet — those are the two branches of <code>CETDeployerImpl#deploy</code>
 * that call <code>_getPortletId</code>. For every other type this endpoint
 * reports <code>hasPortlet: false</code> and a null <code>portletId</code>
 * rather than composing an id for a portlet that was never registered.
 * </p>
 *
 * <p>
 * Entries are resolved through {@link CETManager} rather than
 * {@link ClientExtensionEntryLocalService}. The local service sees only entries
 * created through Client Extension Admin; extensions deployed as a workspace
 * <code>.zip</code> exist as OSGi configuration and have no
 * <code>ClientExtensionEntry</code> row, so the local service alone returns
 * null for exactly the deployment style that needs this endpoint.
 * {@link CETManager#getCET} consults the database first and the configuration
 * map second, covering both. The local service is still consulted, but only to
 * report <code>entryId</code> and to distinguish
 * <code>sourceType</code>.
 * </p>
 *
 * <p>
 * Authorisation follows this workspace's rule of matching permissions to
 * effects. These are reads of a company-scoped administrative object, so a
 * caller needs omniadmin, company admin, VIEW on the
 * <code>ClientExtensionEntry</code> model when the entry is database-backed, or
 * VIEW on the <code>com.liferay.client.extension</code> portlet resource. That
 * mirrors what Liferay's own <code>ClientExtensionEntryServiceImpl</code>
 * enforces for a read.
 * </p>
 */
public class ClientExtensionEntryResource {

	public ClientExtensionEntryResource(
		CETManager cetManager,
		ClientExtensionEntryLocalService clientExtensionEntryLocalService) {

		_cetManager = cetManager;
		_clientExtensionEntryLocalService = clientExtensionEntryLocalService;
	}

	/**
	 * Unauthenticated deployment liveness/readiness probe.
	 * Confirms the bundle is active and the whiteboard endpoint is mounted.
	 */
	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	public Response status() {
		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		jsonObject.put("status", "active");
		jsonObject.put("module", "client-extension-entry");

		return Response.ok(
			jsonObject.toString(), MediaType.APPLICATION_JSON).build();
	}

	@GET
	@Path("/entries/{externalReferenceCode}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getEntry(
		@Context HttpServletRequest httpServletRequest,
		@PathParam("externalReferenceCode") String externalReferenceCode) {

		try {
			if (Validator.isNull(externalReferenceCode)) {
				return _jsonError(
					Response.Status.BAD_REQUEST, "BadRequest",
					"Parameter 'externalReferenceCode' must not be blank.");
			}

			Response authenticationResponse = _checkAuthentication(
				httpServletRequest);

			if (authenticationResponse != null) {
				return authenticationResponse;
			}

			long companyId = PortalUtil.getCompanyId(httpServletRequest);

			ClientExtensionEntry clientExtensionEntry =
				_fetchClientExtensionEntry(externalReferenceCode, companyId);

			// Authorise before resolving the extension, so that a caller
			// without permission cannot use the difference between 403 and 404
			// to discover which external reference codes exist.

			Response authorizationResponse = _checkAuthorization(
				companyId, clientExtensionEntry);

			if (authorizationResponse != null) {
				return authorizationResponse;
			}

			CET cet = _cetManager.getCET(companyId, externalReferenceCode);

			// A missing external reference code is a 404, never a thrown
			// NullPointerException. Commerce #649 is open against exactly that
			// failure mode and is not worth repeating here.

			if (cet == null) {
				return _jsonError(
					Response.Status.NOT_FOUND, "NotFound",
					"No client extension entry found for external reference " +
						"code " + externalReferenceCode + ".");
			}

			return Response.ok(
				_toJSONObject(
					cet, clientExtensionEntry
				).toString(),
				MediaType.APPLICATION_JSON
			).build();
		}
		catch (Exception exception) {
			_log.error(
				"Failed to retrieve client extension entry " +
					externalReferenceCode,
				exception);

			return _jsonError(
				Response.Status.INTERNAL_SERVER_ERROR, "InternalServerError",
				"An unexpected error occurred while retrieving the client " +
					"extension entry.");
		}
	}

	/**
	 * Lists the client extensions actually deployed to this company, which is
	 * what a configuration panel needs in order to report what is present
	 * rather than what it hopes is present.
	 */
	@GET
	@Path("/entries")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getEntries(
		@Context HttpServletRequest httpServletRequest,
		@QueryParam("keywords") String keywords,
		@QueryParam("type") String type,
		@QueryParam("page") @DefaultValue("1") int page,
		@QueryParam("pageSize") @DefaultValue("100") int pageSize) {

		try {
			if ((page < 1) || (pageSize < 1) || (pageSize > _MAX_PAGE_SIZE)) {
				return _jsonError(
					Response.Status.BAD_REQUEST, "BadRequest",
					"Parameter 'page' must be at least 1 and 'pageSize' " +
						"between 1 and " + _MAX_PAGE_SIZE + ".");
			}

			Response authenticationResponse = _checkAuthentication(
				httpServletRequest);

			if (authenticationResponse != null) {
				return authenticationResponse;
			}

			long companyId = PortalUtil.getCompanyId(httpServletRequest);

			// The listing spans database-backed and configuration-backed
			// entries, so there is no per-entry model permission to fall back
			// on. Authorise once, at the company scope the listing covers.

			Response authorizationResponse = _checkAuthorization(
				companyId, null);

			if (authorizationResponse != null) {
				return authorizationResponse;
			}

			List<CET> cets = _cetManager.getCETs(
				companyId, keywords, type, Pagination.of(page, pageSize), null);

			JSONArray entriesJSONArray = JSONFactoryUtil.createJSONArray();

			for (CET cet : cets) {
				entriesJSONArray.put(
					_toJSONObject(
						cet,
						_fetchClientExtensionEntry(
							cet.getExternalReferenceCode(), companyId)));
			}

			JSONObject responseJSONObject = JSONFactoryUtil.createJSONObject();

			responseJSONObject.put("companyId", companyId);
			responseJSONObject.put("page", page);
			responseJSONObject.put("pageSize", pageSize);
			responseJSONObject.put(
				"totalCount",
				_cetManager.getCETsCount(companyId, keywords, type));
			responseJSONObject.put("entries", entriesJSONArray);

			return Response.ok(
				responseJSONObject.toString(), MediaType.APPLICATION_JSON
			).build();
		}
		catch (Exception exception) {
			_log.error("Failed to list client extension entries", exception);

			return _jsonError(
				Response.Status.INTERNAL_SERVER_ERROR, "InternalServerError",
				"An unexpected error occurred while listing client extension " +
					"entries.");
		}
	}

	private Response _checkAuthentication(
			HttpServletRequest httpServletRequest)
		throws PortalException {

		User user = PortalUtil.getUser(httpServletRequest);

		if ((user == null) || user.isDefaultUser()) {
			return _jsonError(
				Response.Status.UNAUTHORIZED, "Unauthorized",
				"Authentication is required to access client extension " +
					"entries.");
		}

		return null;
	}

	private Response _checkAuthorization(
		long companyId, ClientExtensionEntry clientExtensionEntry) {

		PermissionChecker permissionChecker =
			PermissionThreadLocal.getPermissionChecker();

		if (permissionChecker != null) {
			if (permissionChecker.isOmniadmin() ||
				permissionChecker.isCompanyAdmin(companyId) ||
				permissionChecker.hasPermission(
					0L, ClientExtensionConstants.RESOURCE_NAME, companyId,
					ActionKeys.VIEW)) {

				return null;
			}

			// Liferay's own ClientExtensionEntryModelResourcePermission checks
			// VIEW with a null Group, the entry id as the primary key. Only a
			// database-backed entry has one; a configuration-backed extension
			// is covered by the portlet resource check above.

			if ((clientExtensionEntry != null) &&
				permissionChecker.hasPermission(
					(Group)null, ClientExtensionEntry.class.getName(),
					clientExtensionEntry.getClientExtensionEntryId(),
					ActionKeys.VIEW)) {

				return null;
			}
		}

		return _jsonError(
			Response.Status.FORBIDDEN, "Forbidden",
			"Omniadmin, company admin, or VIEW permission on the client " +
				"extension is required to access client extension entries.");
	}

	private ClientExtensionEntry _fetchClientExtensionEntry(
		String externalReferenceCode, long companyId) {

		try {
			return _clientExtensionEntryLocalService.
				fetchClientExtensionEntryByExternalReferenceCode(
					externalReferenceCode, companyId);
		}
		catch (Exception exception) {
			if (_log.isDebugEnabled()) {
				_log.debug(
					"Unable to fetch client extension entry " +
						externalReferenceCode,
					exception);
			}

			return null;
		}
	}

	private String _getFriendlyURLMapping(CET cet) {
		if (cet instanceof CustomElementCET) {
			return ((CustomElementCET)cet).getFriendlyURLMapping();
		}

		if (cet instanceof IFrameCET) {
			return ((IFrameCET)cet).getFriendlyURLMapping();
		}

		return null;
	}

	/**
	 * Composes the portlet id exactly as
	 * <code>CETDeployerImpl#_getPortletId</code> does, for the two types that
	 * register a portlet. Returns <code>null</code> for every other type.
	 */
	private String _getPortletId(CET cet) {
		if (!_hasPortlet(cet)) {
			return null;
		}

		return _PORTLET_ID_PREFIX + cet.getCompanyId() + "_" +
			CETUtil.normalizeExternalReferenceCodeForPortletId(
				cet.getExternalReferenceCode());
	}

	private boolean _hasPortlet(CET cet) {
		return Objects.equals(
			cet.getType(), ClientExtensionEntryConstants.TYPE_CUSTOM_ELEMENT) ||
			   Objects.equals(
				   cet.getType(), ClientExtensionEntryConstants.TYPE_IFRAME);
	}

	private boolean _isInstanceable(CET cet) {
		if (cet instanceof CustomElementCET) {
			return ((CustomElementCET)cet).isInstanceable();
		}

		if (cet instanceof IFrameCET) {
			return ((IFrameCET)cet).isInstanceable();
		}

		return false;
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

	private JSONObject _toJSONObject(
		CET cet, ClientExtensionEntry clientExtensionEntry) {

		JSONObject jsonObject = JSONFactoryUtil.createJSONObject();

		jsonObject.put("externalReferenceCode", cet.getExternalReferenceCode());
		jsonObject.put("companyId", cet.getCompanyId());

		// entryId is reported because it is asked for, and null whenever the
		// extension is configuration-backed. It is deliberately not part of
		// portletId; see the class javadoc.

		if (clientExtensionEntry == null) {
			jsonObject.put("entryId", (Object)null);
			jsonObject.put("sourceType", "CONFIGURATION");
		}
		else {
			jsonObject.put(
				"entryId", clientExtensionEntry.getClientExtensionEntryId());
			jsonObject.put("sourceType", "DATABASE");
		}

		jsonObject.put("name", cet.getName());
		jsonObject.put("description", cet.getDescription());
		jsonObject.put("type", cet.getType());
		jsonObject.put("status", cet.getStatus());
		jsonObject.put(
			"statusLabel", WorkflowConstants.getStatusLabel(cet.getStatus()));
		jsonObject.put("sourceCodeURL", cet.getSourceCodeURL());
		jsonObject.put("baseURL", cet.getBaseURL());

		boolean hasPortlet = _hasPortlet(cet);

		jsonObject.put("hasPortlet", hasPortlet);
		jsonObject.put("portletId", _getPortletId(cet));

		if (hasPortlet) {
			jsonObject.put("instanceable", _isInstanceable(cet));
			jsonObject.put("friendlyURLMapping", _getFriendlyURLMapping(cet));
		}

		return jsonObject;
	}

	private static final int _MAX_PAGE_SIZE = 200;

	private static final String _PORTLET_ID_PREFIX =
		"com_liferay_client_extension_web_internal_portlet_" +
			"ClientExtensionEntryPortlet_";

	private static final Log _log = LogFactoryUtil.getLog(
		ClientExtensionEntryResource.class);

	private final CETManager _cetManager;
	private final ClientExtensionEntryLocalService
		_clientExtensionEntryLocalService;

}
