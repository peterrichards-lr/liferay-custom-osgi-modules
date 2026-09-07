package com.liferay.client.extension.entry;

import com.liferay.client.extension.model.ClientExtensionEntry;
import com.liferay.client.extension.service.ClientExtensionEntryLocalService;
import com.liferay.client.extension.type.CET;
import com.liferay.client.extension.type.CustomElementCET;
import com.liferay.client.extension.type.GlobalCSSCET;
import com.liferay.client.extension.type.IFrameCET;
import com.liferay.client.extension.type.manager.CETManager;
import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.security.permission.ActionKeys;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.kernel.util.PortalUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class ClientExtensionEntryResourceTest {

	@Before
	public void setUp() throws Exception {
		RuntimeDelegate runtimeDelegate = Mockito.mock(RuntimeDelegate.class);
		Mockito.when(runtimeDelegate.createResponseBuilder()).thenAnswer(
			invocation -> new DummyResponseBuilder());
		RuntimeDelegate.setInstance(runtimeDelegate);

		JSONFactory jsonFactory = Mockito.mock(JSONFactory.class);
		Mockito.when(jsonFactory.createJSONObject()).thenAnswer(
			invocation -> _createMockJSONObject());
		Mockito.when(jsonFactory.createJSONArray()).thenAnswer(
			invocation -> _createMockJSONArray());
		new JSONFactoryUtil().setJSONFactory(jsonFactory);

		_portal = Mockito.mock(Portal.class);
		new PortalUtil().setPortal(_portal);

		_httpServletRequest = Mockito.mock(HttpServletRequest.class);
		Mockito.when(_portal.getCompanyId(_httpServletRequest)).thenReturn(
			_COMPANY_ID);

		_cetManager = Mockito.mock(CETManager.class);
		_clientExtensionEntryLocalService = Mockito.mock(
			ClientExtensionEntryLocalService.class);

		_resource = new ClientExtensionEntryResource(
			_cetManager, _clientExtensionEntryLocalService);
	}

	@After
	public void tearDown() {
		PermissionThreadLocal.setPermissionChecker(null);
	}

	@Test
	public void testStatus_ReturnsActiveStatus() {
		Response response = _resource.status();

		Assert.assertEquals(
			Response.Status.OK.getStatusCode(), response.getStatus());

		String json = response.getEntity().toString();

		Assert.assertTrue(json.contains("\"status\":\"active\""));
		Assert.assertTrue(
			json.contains("\"module\":\"client-extension-entry\""));
	}

	@Test
	public void testGetEntry_BlankExternalReferenceCode_ReturnsBadRequest() {
		Response response = _resource.getEntry(_httpServletRequest, "  ");

		Assert.assertEquals(
			Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
		Assert.assertTrue(
			response.getEntity().toString().contains(
				"\"error\":\"BadRequest\""));
	}

	@Test
	public void testGetEntry_Unauthenticated_ReturnsUnauthorized()
		throws Exception {

		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(null);

		Response response = _resource.getEntry(_httpServletRequest, _ERC);

		Assert.assertEquals(
			Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
		Assert.assertTrue(
			response.getEntity().toString().contains(
				"\"error\":\"Unauthorized\""));
	}

	@Test
	public void testGetEntry_DefaultUser_ReturnsUnauthorized()
		throws Exception {

		User defaultUser = Mockito.mock(User.class);

		Mockito.when(defaultUser.isDefaultUser()).thenReturn(true);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(
			defaultUser);

		Response response = _resource.getEntry(_httpServletRequest, _ERC);

		Assert.assertEquals(
			Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
	}

	@Test
	public void testGetEntry_AuthenticatedWithoutPermission_ReturnsForbidden()
		throws Exception {

		_setUpAuthenticatedUser();
		_setUpPermissionChecker(false, false, false);

		CustomElementCET customElementCET = _customElementCET(_ERC, false);

		Mockito.when(_cetManager.getCET(_COMPANY_ID, _ERC)).thenReturn(
			customElementCET);

		Response response = _resource.getEntry(_httpServletRequest, _ERC);

		Assert.assertEquals(
			Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
		Assert.assertTrue(
			response.getEntity().toString().contains(
				"\"error\":\"Forbidden\""));
	}

	/**
	 * An unknown external reference code must not leak through the difference
	 * between 403 and 404: an unauthorised caller sees 403 either way.
	 */
	@Test
	public void testGetEntry_UnknownCodeWithoutPermission_ReturnsForbidden()
		throws Exception {

		_setUpAuthenticatedUser();
		_setUpPermissionChecker(false, false, false);

		Mockito.when(_cetManager.getCET(_COMPANY_ID, "nope")).thenReturn(null);

		Response response = _resource.getEntry(_httpServletRequest, "nope");

		Assert.assertEquals(
			Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
	}

	@Test
	public void testGetEntry_UnknownCode_ReturnsNotFoundWithoutThrowing()
		throws Exception {

		_setUpOmniadmin();

		Mockito.when(_cetManager.getCET(_COMPANY_ID, "nope")).thenReturn(null);

		Response response = _resource.getEntry(_httpServletRequest, "nope");

		Assert.assertEquals(
			Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
		Assert.assertTrue(
			response.getEntity().toString().contains("\"error\":\"NotFound\""));
	}

	@Test
	public void testGetEntry_PortletResourceViewPermission_Succeeds()
		throws Exception {

		_setUpAuthenticatedUser();

		PermissionChecker permissionChecker = _setUpPermissionChecker(
			false, false, false);

		Mockito.when(
			permissionChecker.hasPermission(
				0L, "com.liferay.client.extension", _COMPANY_ID,
				ActionKeys.VIEW)).thenReturn(true);

		CustomElementCET customElementCET = _customElementCET(_ERC, false);

		Mockito.when(_cetManager.getCET(_COMPANY_ID, _ERC)).thenReturn(
			customElementCET);

		Response response = _resource.getEntry(_httpServletRequest, _ERC);

		Assert.assertEquals(
			Response.Status.OK.getStatusCode(), response.getStatus());
	}

	@Test
	public void testGetEntry_ModelViewPermissionOnDatabaseEntry_Succeeds()
		throws Exception {

		_setUpAuthenticatedUser();

		PermissionChecker permissionChecker = _setUpPermissionChecker(
			false, false, false);

		Mockito.when(
			permissionChecker.hasPermission(
				(Group)null,
				"com.liferay.client.extension.model.ClientExtensionEntry",
				_ENTRY_ID, ActionKeys.VIEW)).thenReturn(true);

		CustomElementCET customElementCET = _customElementCET(_ERC, false);

		Mockito.when(_cetManager.getCET(_COMPANY_ID, _ERC)).thenReturn(
			customElementCET);

		Response response = _resource.getEntry(_httpServletRequest, _ERC);

		Assert.assertEquals(
			Response.Status.OK.getStatusCode(), response.getStatus());

		String json = response.getEntity().toString();

		Assert.assertTrue(json, json.contains("\"entryId\":" + _ENTRY_ID));
		Assert.assertTrue(json.contains("\"sourceType\":\"DATABASE\""));
	}

	/**
	 * The regression this module exists for. The portlet id embeds the
	 * <em>company</em> id, never the client extension entry id, and the
	 * external reference code is normalised by replacing every non-word
	 * character with an underscore.
	 */
	@Test
	public void testGetEntry_ComposesPortletIdFromCompanyIdNotEntryId()
		throws Exception {

		_setUpOmniadmin();

		CustomElementCET customElementCET = _customElementCET(_ERC, false);

		Mockito.when(_cetManager.getCET(_COMPANY_ID, _ERC)).thenReturn(
			customElementCET);

		Response response = _resource.getEntry(_httpServletRequest, _ERC);

		String json = response.getEntity().toString();

		Assert.assertTrue(
			json,
			json.contains(
				"\"portletId\":\"com_liferay_client_extension_web_internal_" +
					"portlet_ClientExtensionEntryPortlet_99367122642203_LXC_" +
						"liferay_ai_commerce_accelerator_configuration\""));
		Assert.assertFalse(
			json, json.contains("ClientExtensionEntryPortlet_" + _ENTRY_ID));
		Assert.assertTrue(json.contains("\"companyId\":" + _COMPANY_ID));
		Assert.assertTrue(json.contains("\"hasPortlet\":true"));
	}

	@Test
	public void testGetEntry_ConfigurationBackedEntry_ReportsNullEntryId()
		throws Exception {

		_setUpOmniadmin();

		CustomElementCET customElementCET = _customElementCET(_ERC, true);

		Mockito.when(_cetManager.getCET(_COMPANY_ID, _ERC)).thenReturn(
			customElementCET);

		Response response = _resource.getEntry(_httpServletRequest, _ERC);

		String json = response.getEntity().toString();

		Assert.assertTrue(json, json.contains("\"entryId\":null"));
		Assert.assertTrue(json.contains("\"sourceType\":\"CONFIGURATION\""));
		Assert.assertTrue(json.contains("\"instanceable\":true"));
		Assert.assertTrue(
			json.contains("\"friendlyURLMapping\":\"aica-configuration\""));
	}

	@Test
	public void testGetEntry_IFrameEntry_HasPortletId() throws Exception {
		_setUpOmniadmin();

		IFrameCET iFrameCET = Mockito.mock(IFrameCET.class);

		Mockito.when(iFrameCET.getCompanyId()).thenReturn(_COMPANY_ID);
		Mockito.when(iFrameCET.getExternalReferenceCode()).thenReturn(
			"an-iframe");
		Mockito.when(iFrameCET.getType()).thenReturn("iframe");

		Mockito.when(_cetManager.getCET(_COMPANY_ID, "an-iframe")).thenReturn(
			iFrameCET);

		Response response = _resource.getEntry(
			_httpServletRequest, "an-iframe");

		String json = response.getEntity().toString();

		Assert.assertTrue(json.contains("\"hasPortlet\":true"));
		Assert.assertTrue(
			json,
			json.contains(
				"ClientExtensionEntryPortlet_99367122642203_an_iframe"));
	}

	/**
	 * Only <code>customElement</code> and <code>iframe</code> extensions
	 * register a portlet. Composing an id for any other type would be a
	 * fiction, so the endpoint reports none.
	 */
	@Test
	public void testGetEntry_NonPortletType_ReportsNoPortletId()
		throws Exception {

		_setUpOmniadmin();

		GlobalCSSCET globalCSSCET = Mockito.mock(GlobalCSSCET.class);

		Mockito.when(globalCSSCET.getCompanyId()).thenReturn(_COMPANY_ID);
		Mockito.when(globalCSSCET.getExternalReferenceCode()).thenReturn(
			"a-css");
		Mockito.when(globalCSSCET.getType()).thenReturn("globalCSS");

		Mockito.when(_cetManager.getCET(_COMPANY_ID, "a-css")).thenReturn(
			globalCSSCET);

		Response response = _resource.getEntry(_httpServletRequest, "a-css");

		String json = response.getEntity().toString();

		Assert.assertTrue(json, json.contains("\"hasPortlet\":false"));
		Assert.assertTrue(json, json.contains("\"portletId\":null"));
		Assert.assertFalse(json, json.contains("instanceable"));
	}

	@Test
	public void testGetEntry_ManagerFailure_ReturnsInternalServerError()
		throws Exception {

		_setUpOmniadmin();

		Mockito.when(_cetManager.getCET(_COMPANY_ID, _ERC)).thenThrow(
			new RuntimeException("boom"));

		Response response = _resource.getEntry(_httpServletRequest, _ERC);

		Assert.assertEquals(
			Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
			response.getStatus());
		Assert.assertTrue(
			response.getEntity().toString().contains(
				"\"error\":\"InternalServerError\""));
	}

	@Test
	public void testGetEntries_ListsDeployedExtensions() throws Exception {
		_setUpOmniadmin();

		List<CET> cets = Arrays.asList((CET)_customElementCET(_ERC, true));

		Mockito.when(
			_cetManager.getCETs(
				Mockito.eq(_COMPANY_ID), Mockito.isNull(), Mockito.isNull(),
				Mockito.any(), Mockito.isNull())).thenReturn(cets);
		Mockito.when(
			_cetManager.getCETsCount(_COMPANY_ID, null, null)).thenReturn(1);

		Response response = _resource.getEntries(
			_httpServletRequest, null, null, 1, 100);

		Assert.assertEquals(
			Response.Status.OK.getStatusCode(), response.getStatus());

		String json = response.getEntity().toString();

		Assert.assertTrue(json, json.contains("\"totalCount\":1"));
		Assert.assertTrue(json.contains("\"page\":1"));
		Assert.assertTrue(json.contains("\"pageSize\":100"));
		Assert.assertTrue(
			json,
			json.contains(
				"ClientExtensionEntryPortlet_99367122642203_LXC_liferay_ai_" +
					"commerce_accelerator_configuration"));
	}

	@Test
	public void testGetEntries_InvalidPagination_ReturnsBadRequest()
		throws Exception {

		_setUpOmniadmin();

		Assert.assertEquals(
			Response.Status.BAD_REQUEST.getStatusCode(),
			_resource.getEntries(
				_httpServletRequest, null, null, 0, 100).getStatus());
		Assert.assertEquals(
			Response.Status.BAD_REQUEST.getStatusCode(),
			_resource.getEntries(
				_httpServletRequest, null, null, 1, 0).getStatus());
		Assert.assertEquals(
			Response.Status.BAD_REQUEST.getStatusCode(),
			_resource.getEntries(
				_httpServletRequest, null, null, 1, 201).getStatus());
	}

	@Test
	public void testGetEntries_Unauthenticated_ReturnsUnauthorized()
		throws Exception {

		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(null);

		Response response = _resource.getEntries(
			_httpServletRequest, null, null, 1, 100);

		Assert.assertEquals(
			Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
	}

	@Test
	public void testGetEntries_WithoutPermission_ReturnsForbidden()
		throws Exception {

		_setUpAuthenticatedUser();
		_setUpPermissionChecker(false, false, false);

		Response response = _resource.getEntries(
			_httpServletRequest, null, null, 1, 100);

		Assert.assertEquals(
			Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
	}

	private JSONArray _createMockJSONArray() {
		JSONArray jsonArray = Mockito.mock(JSONArray.class);

		List<Object> list = new ArrayList<>();

		Mockito.doAnswer(
			invocation -> {
				list.add(invocation.getArgument(0));

				return jsonArray;
			}).when(jsonArray).put(Mockito.any(JSONObject.class));

		Mockito.when(jsonArray.toString()).thenAnswer(
			invocation -> {
				StringBuilder sb = new StringBuilder("[");

				for (int i = 0; i < list.size(); i++) {
					if (i > 0) {
						sb.append(",");
					}

					sb.append(list.get(i));
				}

				sb.append("]");

				return sb.toString();
			});

		return jsonArray;
	}

	private JSONObject _createMockJSONObject() {
		JSONObject jsonObject = Mockito.mock(JSONObject.class);

		Map<String, Object> map = new LinkedHashMap<>();

		Mockito.doAnswer(
			invocation -> {
				map.put(invocation.getArgument(0), invocation.getArgument(1));

				return jsonObject;
			}).when(jsonObject).put(
				Mockito.anyString(), Mockito.nullable(String.class));

		Mockito.doAnswer(
			invocation -> {
				map.put(invocation.getArgument(0), invocation.getArgument(1));

				return jsonObject;
			}).when(jsonObject).put(Mockito.anyString(), Mockito.anyBoolean());

		Mockito.doAnswer(
			invocation -> {
				map.put(invocation.getArgument(0), invocation.getArgument(1));

				return jsonObject;
			}).when(jsonObject).put(Mockito.anyString(), Mockito.anyLong());

		Mockito.doAnswer(
			invocation -> {
				map.put(invocation.getArgument(0), invocation.getArgument(1));

				return jsonObject;
			}).when(jsonObject).put(Mockito.anyString(), Mockito.anyInt());

		Mockito.doAnswer(
			invocation -> {
				map.put(invocation.getArgument(0), invocation.getArgument(1));

				return jsonObject;
			}).when(jsonObject).put(
				Mockito.anyString(), Mockito.any(JSONArray.class));

		Mockito.doAnswer(
			invocation -> {
				map.put(invocation.getArgument(0), invocation.getArgument(1));

				return jsonObject;
			}).when(jsonObject).put(
				Mockito.anyString(), Mockito.nullable(Object.class));

		Mockito.when(jsonObject.toString()).thenAnswer(
			invocation -> {
				StringBuilder sb = new StringBuilder("{");

				boolean first = true;

				for (Map.Entry<String, Object> entry : map.entrySet()) {
					if (!first) {
						sb.append(",");
					}

					sb.append("\"").append(entry.getKey()).append("\":");

					Object value = entry.getValue();

					if (value == null) {
						sb.append("null");
					}
					else if (value instanceof String) {
						sb.append("\"").append(value).append("\"");
					}
					else {
						sb.append(value);
					}

					first = false;
				}

				sb.append("}");

				return sb.toString();
			});

		return jsonObject;
	}

	private CustomElementCET _customElementCET(
		String externalReferenceCode, boolean configurationBacked) {

		CustomElementCET customElementCET = Mockito.mock(
			CustomElementCET.class);

		Mockito.when(customElementCET.getCompanyId()).thenReturn(_COMPANY_ID);
		Mockito.when(customElementCET.getExternalReferenceCode()).thenReturn(
			externalReferenceCode);
		Mockito.when(customElementCET.getType()).thenReturn("customElement");
		Mockito.when(customElementCET.getName()).thenReturn(
			"AICA Configuration");
		Mockito.when(customElementCET.getStatus()).thenReturn(0);
		Mockito.when(customElementCET.isInstanceable()).thenReturn(true);
		Mockito.when(customElementCET.getFriendlyURLMapping()).thenReturn(
			"aica-configuration");

		if (!configurationBacked) {
			_setUpDatabaseEntry(externalReferenceCode);
		}

		return customElementCET;
	}

	private void _setUpAuthenticatedUser() throws Exception {
		User user = Mockito.mock(User.class);

		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(user.getUserId()).thenReturn(2001L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);
	}

	private void _setUpDatabaseEntry(String externalReferenceCode) {
		ClientExtensionEntry clientExtensionEntry = Mockito.mock(
			ClientExtensionEntry.class);

		Mockito.when(
			clientExtensionEntry.getClientExtensionEntryId()).thenReturn(
				_ENTRY_ID);

		Mockito.when(
			_clientExtensionEntryLocalService.
				fetchClientExtensionEntryByExternalReferenceCode(
					externalReferenceCode, _COMPANY_ID)).thenReturn(
						clientExtensionEntry);
	}

	private void _setUpOmniadmin() throws Exception {
		_setUpAuthenticatedUser();
		_setUpPermissionChecker(true, false, false);
	}

	private PermissionChecker _setUpPermissionChecker(
		boolean omniadmin, boolean companyAdmin, boolean anyPermission) {

		PermissionChecker permissionChecker = Mockito.mock(
			PermissionChecker.class);

		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(omniadmin);
		Mockito.when(
			permissionChecker.isCompanyAdmin(Mockito.anyLong())).thenReturn(
				companyAdmin);
		Mockito.when(
			permissionChecker.hasPermission(
				Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong(),
				Mockito.anyString())).thenReturn(anyPermission);
		Mockito.when(
			permissionChecker.hasPermission(
				Mockito.nullable(Group.class), Mockito.anyString(),
				Mockito.anyLong(), Mockito.anyString())).thenReturn(
					anyPermission);

		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		return permissionChecker;
	}

	private static final long _COMPANY_ID = 99367122642203L;

	private static final long _ENTRY_ID = 30394841094851L;

	private static final String _ERC =
		"LXC_liferay-ai-commerce-accelerator-configuration";

	private CETManager _cetManager;
	private ClientExtensionEntryLocalService _clientExtensionEntryLocalService;
	private HttpServletRequest _httpServletRequest;
	private Portal _portal;
	private ClientExtensionEntryResource _resource;

	private static class DummyResponseBuilder extends Response.ResponseBuilder {

		@Override
		public Response.ResponseBuilder allow(java.util.Set<String> methods) {
			return this;
		}

		@Override
		public Response.ResponseBuilder allow(String... methods) {
			return this;
		}

		@Override
		public Response build() {
			final int currentStatus = _status;
			final Object currentEntity = _entity;
			final MediaType currentType = _type;

			return new Response() {

				@Override
				public boolean bufferEntity() {
					return false;
				}

				@Override
				public void close() {
				}

				@Override
				public java.util.Set<String> getAllowedMethods() {
					return Collections.emptySet();
				}

				@Override
				public Map<String, jakarta.ws.rs.core.NewCookie> getCookies() {
					return Collections.emptyMap();
				}

				@Override
				public java.util.Date getDate() {
					return null;
				}

				@Override
				public Object getEntity() {
					return currentEntity;
				}

				@Override
				public jakarta.ws.rs.core.EntityTag getEntityTag() {
					return null;
				}

				@Override
				public String getHeaderString(String name) {
					return null;
				}

				@Override
				public java.util.Locale getLanguage() {
					return null;
				}

				@Override
				public java.util.Date getLastModified() {
					return null;
				}

				@Override
				public int getLength() {
					return 0;
				}

				@Override
				public jakarta.ws.rs.core.Link getLink(String relation) {
					return null;
				}

				@Override
				public jakarta.ws.rs.core.Link.Builder getLinkBuilder(
					String relation) {

					return null;
				}

				@Override
				public java.util.Set<jakarta.ws.rs.core.Link> getLinks() {
					return Collections.emptySet();
				}

				@Override
				public java.net.URI getLocation() {
					return null;
				}

				@Override
				public MediaType getMediaType() {
					return currentType;
				}

				@Override
				public jakarta.ws.rs.core.MultivaluedMap<String, Object>
					getMetadata() {

					return null;
				}

				@Override
				public int getStatus() {
					return currentStatus;
				}

				@Override
				public StatusType getStatusInfo() {
					return Response.Status.fromStatusCode(currentStatus);
				}

				@Override
				public jakarta.ws.rs.core.MultivaluedMap<String, String>
					getStringHeaders() {

					return null;
				}

				@Override
				public boolean hasEntity() {
					return currentEntity != null;
				}

				@Override
				public boolean hasLink(String relation) {
					return false;
				}

				@Override
				@SuppressWarnings("unchecked")
				public <T> T readEntity(Class<T> entityType) {
					return (T)currentEntity;
				}

				@Override
				@SuppressWarnings("unchecked")
				public <T> T readEntity(
					Class<T> entityType,
					java.lang.annotation.Annotation[] annotations) {

					return (T)currentEntity;
				}

				@Override
				@SuppressWarnings("unchecked")
				public <T> T readEntity(
					jakarta.ws.rs.core.GenericType<T> entityType) {

					return (T)currentEntity;
				}

				@Override
				@SuppressWarnings("unchecked")
				public <T> T readEntity(
					jakarta.ws.rs.core.GenericType<T> entityType,
					java.lang.annotation.Annotation[] annotations) {

					return (T)currentEntity;
				}

			};
		}

		@Override
		public Response.ResponseBuilder cacheControl(
			jakarta.ws.rs.core.CacheControl cacheControl) {

			return this;
		}

		@Override
		public Response.ResponseBuilder clone() {
			return this;
		}

		@Override
		public Response.ResponseBuilder contentLocation(
			java.net.URI location) {

			return this;
		}

		@Override
		public Response.ResponseBuilder cookie(
			jakarta.ws.rs.core.NewCookie... cookies) {

			return this;
		}

		@Override
		public Response.ResponseBuilder encoding(String encoding) {
			return this;
		}

		@Override
		public Response.ResponseBuilder entity(Object entity) {
			_entity = entity;

			return this;
		}

		@Override
		public Response.ResponseBuilder entity(
			Object entity, java.lang.annotation.Annotation[] annotations) {

			_entity = entity;

			return this;
		}

		@Override
		public Response.ResponseBuilder expires(java.util.Date date) {
			return this;
		}

		@Override
		public Response.ResponseBuilder header(String name, Object value) {
			return this;
		}

		@Override
		public Response.ResponseBuilder language(java.util.Locale locale) {
			return this;
		}

		@Override
		public Response.ResponseBuilder language(String language) {
			return this;
		}

		@Override
		public Response.ResponseBuilder lastModified(java.util.Date date) {
			return this;
		}

		@Override
		public Response.ResponseBuilder link(java.net.URI uri, String rel) {
			return this;
		}

		@Override
		public Response.ResponseBuilder link(String uri, String rel) {
			return this;
		}

		@Override
		public Response.ResponseBuilder links(
			jakarta.ws.rs.core.Link... links) {

			return this;
		}

		@Override
		public Response.ResponseBuilder location(java.net.URI location) {
			return this;
		}

		@Override
		public Response.ResponseBuilder replaceAll(
			jakarta.ws.rs.core.MultivaluedMap<String, Object> headers) {

			return this;
		}

		@Override
		public Response.ResponseBuilder status(int status) {
			_status = status;

			return this;
		}

		@Override
		public Response.ResponseBuilder status(
			int status, String reasonPhrase) {

			_status = status;

			return this;
		}

		@Override
		public Response.ResponseBuilder tag(
			jakarta.ws.rs.core.EntityTag tag) {

			return this;
		}

		@Override
		public Response.ResponseBuilder tag(String tag) {
			return this;
		}

		@Override
		public Response.ResponseBuilder type(MediaType type) {
			_type = type;

			return this;
		}

		@Override
		public Response.ResponseBuilder type(String type) {
			return this;
		}

		@Override
		public Response.ResponseBuilder variant(
			jakarta.ws.rs.core.Variant variant) {

			return this;
		}

		@Override
		public Response.ResponseBuilder variants(
			java.util.List<jakarta.ws.rs.core.Variant> variants) {

			return this;
		}

		@Override
		public Response.ResponseBuilder variants(
			jakarta.ws.rs.core.Variant... variants) {

			return this;
		}

		private Object _entity;
		private int _status = 200;
		private MediaType _type;

	}

}
