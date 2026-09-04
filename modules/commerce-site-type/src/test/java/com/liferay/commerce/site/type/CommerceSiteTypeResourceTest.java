package com.liferay.commerce.site.type;

import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.service.ClassNameLocalService;
import com.liferay.portal.kernel.service.ClassNameLocalServiceUtil;
import com.liferay.portal.kernel.service.GroupLocalService;
import com.liferay.portal.kernel.service.GroupLocalServiceUtil;
import com.liferay.portal.kernel.settings.ModifiableSettings;
import com.liferay.portal.kernel.settings.Settings;
import com.liferay.portal.kernel.settings.SettingsLocatorHelper;
import com.liferay.portal.kernel.settings.SettingsLocatorHelperUtil;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.kernel.util.PortalUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class CommerceSiteTypeResourceTest {

	@Before
	public void setUp() throws Exception {
		RuntimeDelegate runtimeDelegate = Mockito.mock(RuntimeDelegate.class);
		Mockito.when(runtimeDelegate.createResponseBuilder()).thenAnswer(invocation -> new DummyResponseBuilder());
		RuntimeDelegate.setInstance(runtimeDelegate);

		JSONFactory jsonFactory = Mockito.mock(JSONFactory.class);
		Mockito.when(jsonFactory.createJSONObject()).thenAnswer(invocation -> _createMockJSONObject());
		Mockito.when(jsonFactory.createJSONArray()).thenAnswer(invocation -> _createMockJSONArray());
		new JSONFactoryUtil().setJSONFactory(jsonFactory);

		_portal = Mockito.mock(Portal.class);
		new PortalUtil().setPortal(_portal);

		_classNameLocalService = Mockito.mock(ClassNameLocalService.class);
		ClassNameLocalServiceUtil.setService(_classNameLocalService);
		Mockito.when(_classNameLocalService.getClassNameId("com.liferay.commerce.product.model.CommerceChannel"))
			.thenReturn(12345L);

		_groupLocalService = Mockito.mock(GroupLocalService.class);
		GroupLocalServiceUtil.setService(_groupLocalService);

		_settingsLocatorHelper = Mockito.mock(SettingsLocatorHelper.class);
		_setSettingsLocatorHelperSupplier(() -> _settingsLocatorHelper);

		_httpServletRequest = Mockito.mock(HttpServletRequest.class);
		Mockito.when(_portal.getCompanyId(_httpServletRequest)).thenReturn(1001L);

		_resource = new CommerceSiteTypeResource();
	}

	@After
	public void tearDown() {
		PermissionThreadLocal.setPermissionChecker(null);
	}

	@Test
	public void testStatus_ReturnsActiveStatus() {
		Response response = _resource.status();

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		String json = response.getEntity().toString();
		Assert.assertTrue(json.contains("\"status\":\"active\""));
		Assert.assertTrue(json.contains("\"module\":\"commerce-site-type\""));
	}

	@Test
	public void testGetSiteType_InvalidChannelId_ReturnsBadRequest() {
		Response responseZero = _resource.getSiteType(_httpServletRequest, 0L);
		Assert.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), responseZero.getStatus());
		Assert.assertTrue(responseZero.getEntity().toString().contains("\"error\":\"BadRequest\""));

		Response responseNegative = _resource.getSiteType(_httpServletRequest, -10L);
		Assert.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), responseNegative.getStatus());
		Assert.assertTrue(responseNegative.getEntity().toString().contains("\"error\":\"BadRequest\""));
	}

	@Test
	public void testGetSiteType_Unauthenticated_ReturnsUnauthorized() throws Exception {
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(null);

		Response response = _resource.getSiteType(_httpServletRequest, 34562L);

		Assert.assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"error\":\"Unauthorized\""));
	}

	@Test
	public void testGetSiteType_DefaultUser_ReturnsUnauthorized() throws Exception {
		User defaultUser = Mockito.mock(User.class);
		Mockito.when(defaultUser.isDefaultUser()).thenReturn(true);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(defaultUser);

		Response response = _resource.getSiteType(_httpServletRequest, 34562L);

		Assert.assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"error\":\"Unauthorized\""));
	}

	@Test
	public void testGetSiteType_NonOmniadmin_ReturnsForbidden() throws Exception {
		_setupAuthenticatedUser();

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(false);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _resource.getSiteType(_httpServletRequest, 34562L);

		Assert.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"error\":\"Forbidden\""));
	}

	@Test
	public void testGetSiteType_ChannelGroupNotFound_ReturnsNotFound() throws Exception {
		_setupOmniadmin();
		Mockito.when(_groupLocalService.fetchGroup(1001L, 12345L, 34562L)).thenReturn(null);

		Response response = _resource.getSiteType(_httpServletRequest, 34562L);

		Assert.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"error\":\"NotFound\""));
	}

	@Test
	public void testGetSiteType_ConfiguredB2C_ReturnsSiteType0() throws Exception {
		_setupOmniadmin();
		_setupChannelGroupAndSettings("0", true);

		Response response = _resource.getSiteType(_httpServletRequest, 34562L);

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		String json = response.getEntity().toString();
		Assert.assertTrue(json.contains("\"channelId\":34562"));
		Assert.assertTrue(json.contains("\"siteType\":0"));
		Assert.assertTrue(json.contains("\"siteTypeLabel\":\"B2C\""));
		Assert.assertTrue(json.contains("\"allowedAccountTypes\":[\"person\"]"));
		Assert.assertTrue(json.contains("\"configured\":true"));
	}

	@Test
	public void testGetSiteType_ConfiguredB2B_ReturnsSiteType1() throws Exception {
		_setupOmniadmin();
		_setupChannelGroupAndSettings("1", true);

		Response response = _resource.getSiteType(_httpServletRequest, 34562L);

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		String json = response.getEntity().toString();
		Assert.assertTrue(json.contains("\"channelId\":34562"));
		Assert.assertTrue(json.contains("\"siteType\":1"));
		Assert.assertTrue(json.contains("\"siteTypeLabel\":\"B2B\""));
		Assert.assertTrue(json.contains("\"allowedAccountTypes\":[\"business\",\"supplier\"]"));
		Assert.assertTrue(json.contains("\"configured\":true"));
	}

	@Test
	public void testGetSiteType_ConfiguredB2X_ReturnsSiteType2() throws Exception {
		_setupOmniadmin();
		_setupChannelGroupAndSettings("2", true);

		Response response = _resource.getSiteType(_httpServletRequest, 34562L);

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		String json = response.getEntity().toString();
		Assert.assertTrue(json.contains("\"channelId\":34562"));
		Assert.assertTrue(json.contains("\"siteType\":2"));
		Assert.assertTrue(json.contains("\"siteTypeLabel\":\"B2X\""));
		Assert.assertTrue(json.contains("\"allowedAccountTypes\":[\"business\",\"person\",\"supplier\"]"));
		Assert.assertTrue(json.contains("\"configured\":true"));
	}

	@Test
	public void testGetSiteType_UnconfiguredFallback_ReturnsConfiguredFalse() throws Exception {
		_setupOmniadmin();
		_setupChannelGroupAndSettings("0", false);

		Response response = _resource.getSiteType(_httpServletRequest, 34562L);

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		String json = response.getEntity().toString();
		Assert.assertTrue(json.contains("\"channelId\":34562"));
		Assert.assertTrue(json.contains("\"siteType\":0"));
		Assert.assertTrue(json.contains("\"siteTypeLabel\":\"B2C\""));
		Assert.assertTrue(json.contains("\"allowedAccountTypes\":[\"person\"]"));
		Assert.assertTrue(json.contains("\"configured\":false"));
	}

	@Test
	public void testGetSiteType_UnknownSiteTypeValue_DefaultsToB2C() throws Exception {
		_setupOmniadmin();
		_setupChannelGroupAndSettings("99", true);

		Response response = _resource.getSiteType(_httpServletRequest, 34562L);

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		String json = response.getEntity().toString();
		Assert.assertTrue(json.contains("\"channelId\":34562"));
		Assert.assertTrue(json.contains("\"siteType\":0"));
		Assert.assertTrue(json.contains("\"siteTypeLabel\":\"B2C\""));
		Assert.assertTrue(json.contains("\"allowedAccountTypes\":[\"person\"]"));
	}

	private void _setupOmniadmin() throws Exception {
		_setupAuthenticatedUser();

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);
	}

	private void _setupAuthenticatedUser() throws Exception {
		User user = Mockito.mock(User.class);
		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);
	}

	private void _setupChannelGroupAndSettings(String siteTypeValue, boolean configured) throws Exception {
		long channelId = 34562L;
		long groupId = 34563L;

		Group group = Mockito.mock(Group.class);
		Mockito.when(group.getGroupId()).thenReturn(groupId);
		Mockito.when(group.getCompanyId()).thenReturn(1001L);

		Mockito.when(_groupLocalService.fetchGroup(1001L, 12345L, channelId)).thenReturn(group);
		Mockito.when(_groupLocalService.getGroup(groupId)).thenReturn(group);

		TestModifiableSettings mockSettings = new TestModifiableSettings();
		mockSettings.setValue("commerceSiteType", siteTypeValue);
		if (configured) {
			mockSettings.addModifiedKey("commerceSiteType");
		}

		Mockito.when(_settingsLocatorHelper.getGroupPortletPreferencesSettings(
			Mockito.eq(groupId), Mockito.eq("com.liferay.commerce.account"), Mockito.any()))
			.thenReturn(mockSettings);
	}

	private void _setSettingsLocatorHelperSupplier(Supplier<SettingsLocatorHelper> supplier) throws Exception {
		Field snapshotField = SettingsLocatorHelperUtil.class.getDeclaredField("_settingsLocatorHelperSnapshot");
		snapshotField.setAccessible(true);
		Object snapshot = snapshotField.get(null);

		Field supplierField = snapshot.getClass().getDeclaredField("_serviceSupplier");
		supplierField.setAccessible(true);
		supplierField.set(snapshot, supplier);
	}

	private JSONObject _createMockJSONObject() {
		JSONObject jsonObject = Mockito.mock(JSONObject.class);
		Map<String, Object> map = new LinkedHashMap<>();

		Mockito.doAnswer(invocation -> {
			map.put(invocation.getArgument(0), invocation.getArgument(1));
			return jsonObject;
		}).when(jsonObject).put(Mockito.anyString(), Mockito.any(String.class));

		Mockito.doAnswer(invocation -> {
			map.put(invocation.getArgument(0), invocation.getArgument(1));
			return jsonObject;
		}).when(jsonObject).put(Mockito.anyString(), Mockito.anyBoolean());

		Mockito.doAnswer(invocation -> {
			map.put(invocation.getArgument(0), invocation.getArgument(1));
			return jsonObject;
		}).when(jsonObject).put(Mockito.anyString(), Mockito.anyLong());

		Mockito.doAnswer(invocation -> {
			map.put(invocation.getArgument(0), invocation.getArgument(1));
			return jsonObject;
		}).when(jsonObject).put(Mockito.anyString(), Mockito.anyInt());

		Mockito.doAnswer(invocation -> {
			map.put(invocation.getArgument(0), invocation.getArgument(1));
			return jsonObject;
		}).when(jsonObject).put(Mockito.anyString(), Mockito.any(JSONArray.class));

		Mockito.when(jsonObject.toString()).thenAnswer(invocation -> {
			StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				if (!first) {
					sb.append(",");
				}
				sb.append("\"").append(entry.getKey()).append("\":");
				Object val = entry.getValue();
				if (val instanceof String) {
					sb.append("\"").append(val).append("\"");
				}
				else {
					sb.append(val);
				}
				first = false;
			}
			sb.append("}");
			return sb.toString();
		});

		return jsonObject;
	}

	private JSONArray _createMockJSONArray() {
		JSONArray jsonArray = Mockito.mock(JSONArray.class);
		List<Object> list = new ArrayList<>();

		Mockito.doAnswer(invocation -> {
			list.add(invocation.getArgument(0));
			return jsonArray;
		}).when(jsonArray).put(Mockito.any(String.class));

		Mockito.when(jsonArray.toString()).thenAnswer(invocation -> {
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			for (Object obj : list) {
				if (!first) {
					sb.append(",");
				}
				sb.append("\"").append(obj).append("\"");
				first = false;
			}
			sb.append("]");
			return sb.toString();
		});

		return jsonArray;
	}

	private static class TestModifiableSettings implements ModifiableSettings {
		private final Map<String, String> _values = new LinkedHashMap<>();
		private final Set<String> _modifiedKeys = new HashSet<>();

		public void addModifiedKey(String key) {
			_modifiedKeys.add(key);
		}

		@Override
		public ModifiableSettings setValue(String key, String value) {
			_values.put(key, value);
			return this;
		}

		@Override
		public ModifiableSettings setValues(String key, String[] values) {
			if (values != null && values.length > 0) {
				_values.put(key, values[0]);
			}
			return this;
		}

		@Override
		public ModifiableSettings setValues(ModifiableSettings modifiableSettings) {
			return this;
		}

		@Override
		public Collection<String> getModifiedKeys() {
			return _modifiedKeys;
		}

		@Override
		public void reset() {
			_modifiedKeys.clear();
			_values.clear();
		}

		@Override
		public void reset(String key) {
			_modifiedKeys.remove(key);
			_values.remove(key);
		}

		@Override
		public void store() {}

		@Override
		public ModifiableSettings getModifiableSettings() {
			return this;
		}

		@Override
		public Settings getParentSettings() {
			return null;
		}

		@Override
		public String getValue(String key, String defaultValue) {
			return _values.getOrDefault(key, defaultValue);
		}

		@Override
		public String[] getValues(String key, String[] defaultValues) {
			String val = _values.get(key);
			if (val != null) {
				return new String[]{val};
			}
			return defaultValues;
		}
	}

	private static class DummyResponseBuilder extends Response.ResponseBuilder {
		private int _status = 200;
		private Object _entity;
		private MediaType _type;

		@Override
		public Response build() {
			final int currentStatus = _status;
			final Object currentEntity = _entity;
			final MediaType currentType = _type;

			return new Response() {
				@Override
				public int getStatus() { return currentStatus; }
				@Override
				public StatusType getStatusInfo() { return Response.Status.fromStatusCode(currentStatus); }
				@Override
				public Object getEntity() { return currentEntity; }
				@Override
				public <T> T readEntity(Class<T> entityType) { return (T) currentEntity; }
				@Override
				public <T> T readEntity(jakarta.ws.rs.core.GenericType<T> entityType) { return (T) currentEntity; }
				@Override
				public <T> T readEntity(Class<T> entityType, java.lang.annotation.Annotation[] annotations) { return (T) currentEntity; }
				@Override
				public <T> T readEntity(jakarta.ws.rs.core.GenericType<T> entityType, java.lang.annotation.Annotation[] annotations) { return (T) currentEntity; }
				@Override
				public boolean hasEntity() { return currentEntity != null; }
				@Override
				public boolean bufferEntity() { return false; }
				@Override
				public void close() {}
				@Override
				public MediaType getMediaType() { return currentType; }
				@Override
				public java.util.Locale getLanguage() { return null; }
				@Override
				public int getLength() { return 0; }
				@Override
				public java.util.Set<String> getAllowedMethods() { return null; }
				@Override
				public Map<String, jakarta.ws.rs.core.NewCookie> getCookies() { return null; }
				@Override
				public jakarta.ws.rs.core.EntityTag getEntityTag() { return null; }
				@Override
				public java.util.Date getDate() { return null; }
				@Override
				public java.util.Date getLastModified() { return null; }
				@Override
				public java.net.URI getLocation() { return null; }
				@Override
				public java.util.Set<jakarta.ws.rs.core.Link> getLinks() { return null; }
				@Override
				public boolean hasLink(String relation) { return false; }
				@Override
				public jakarta.ws.rs.core.Link getLink(String relation) { return null; }
				@Override
				public jakarta.ws.rs.core.Link.Builder getLinkBuilder(String relation) { return null; }
				@Override
				public jakarta.ws.rs.core.MultivaluedMap<String, Object> getMetadata() { return null; }
				@Override
				public jakarta.ws.rs.core.MultivaluedMap<String, String> getStringHeaders() { return null; }
				@Override
				public String getHeaderString(String name) { return null; }
			};
		}

		@Override
		public Response.ResponseBuilder clone() { return this; }
		@Override
		public Response.ResponseBuilder status(int status) { _status = status; return this; }
		@Override
		public Response.ResponseBuilder status(int status, String reasonPhrase) { _status = status; return this; }
		@Override
		public Response.ResponseBuilder entity(Object entity) { _entity = entity; return this; }
		@Override
		public Response.ResponseBuilder entity(Object entity, java.lang.annotation.Annotation[] annotations) { _entity = entity; return this; }
		@Override
		public Response.ResponseBuilder allow(String... methods) { return this; }
		@Override
		public Response.ResponseBuilder allow(java.util.Set<String> methods) { return this; }
		@Override
		public Response.ResponseBuilder cacheControl(jakarta.ws.rs.core.CacheControl cacheControl) { return this; }
		@Override
		public Response.ResponseBuilder encoding(String encoding) { return this; }
		@Override
		public Response.ResponseBuilder header(String name, Object value) { return this; }
		@Override
		public Response.ResponseBuilder replaceAll(jakarta.ws.rs.core.MultivaluedMap<String, Object> headers) { return this; }
		@Override
		public Response.ResponseBuilder language(String language) { return this; }
		@Override
		public Response.ResponseBuilder language(java.util.Locale locale) { return this; }
		@Override
		public Response.ResponseBuilder type(MediaType type) { _type = type; return this; }
		@Override
		public Response.ResponseBuilder type(String type) { return this; }
		@Override
		public Response.ResponseBuilder variant(jakarta.ws.rs.core.Variant variant) { return this; }
		@Override
		public Response.ResponseBuilder contentLocation(java.net.URI location) { return this; }
		@Override
		public Response.ResponseBuilder cookie(jakarta.ws.rs.core.NewCookie... cookies) { return this; }
		@Override
		public Response.ResponseBuilder expires(java.util.Date date) { return this; }
		@Override
		public Response.ResponseBuilder lastModified(java.util.Date date) { return this; }
		@Override
		public Response.ResponseBuilder location(java.net.URI location) { return this; }
		@Override
		public Response.ResponseBuilder tag(jakarta.ws.rs.core.EntityTag tag) { return this; }
		@Override
		public Response.ResponseBuilder tag(String tag) { return this; }
		@Override
		public Response.ResponseBuilder variants(jakarta.ws.rs.core.Variant... variants) { return this; }
		@Override
		public Response.ResponseBuilder variants(java.util.List<jakarta.ws.rs.core.Variant> variants) { return this; }
		@Override
		public Response.ResponseBuilder links(jakarta.ws.rs.core.Link... links) { return this; }
		@Override
		public Response.ResponseBuilder link(java.net.URI uri, String rel) { return this; }
		@Override
		public Response.ResponseBuilder link(String uri, String rel) { return this; }
	}

	private ClassNameLocalService _classNameLocalService;
	private GroupLocalService _groupLocalService;
	private HttpServletRequest _httpServletRequest;
	private Portal _portal;
	private CommerceSiteTypeResource _resource;
	private SettingsLocatorHelper _settingsLocatorHelper;

}
