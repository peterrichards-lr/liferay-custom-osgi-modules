package com.liferay.fragment.override.application;

import com.liferay.fragment.model.FragmentEntryLink;
import com.liferay.fragment.service.FragmentEntryLinkLocalService;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.model.Layout;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.security.permission.ActionKeys;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.service.LayoutLocalService;
import com.liferay.portal.kernel.service.permission.LayoutPermission;
import com.liferay.portal.kernel.service.permission.LayoutPermissionUtil;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.portal.kernel.util.PropsUtil;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class FragmentOverrideApplicationTest {

	@Before
	public void setUp() throws Exception {
		RuntimeDelegate runtimeDelegate = Mockito.mock(RuntimeDelegate.class);
		Mockito.when(runtimeDelegate.createResponseBuilder()).thenAnswer(invocation -> new DummyResponseBuilder());
		RuntimeDelegate.setInstance(runtimeDelegate);

		JSONFactory jsonFactory = Mockito.mock(JSONFactory.class);
		Mockito.when(jsonFactory.createJSONObject()).thenAnswer(invocation -> _createMockJSONObject());
		new JSONFactoryUtil().setJSONFactory(jsonFactory);

		_portal = Mockito.mock(Portal.class);
		new PortalUtil().setPortal(_portal);

		_layoutPermission = Mockito.mock(LayoutPermission.class);
		new LayoutPermissionUtil().setLayoutPermission(_layoutPermission);

		_fragmentEntryLinkLocalService = Mockito.mock(FragmentEntryLinkLocalService.class);
		_layoutLocalService = Mockito.mock(LayoutLocalService.class);
		_httpServletRequest = Mockito.mock(HttpServletRequest.class);

		PropsUtil.set("feature.flag.LPD-99955", "false");
		PropsUtil.set("feature.flag.LPS-178052", "false");

		_application = new FragmentOverrideApplication();
		_setField(_application, "_fragmentEntryLinkLocalService", _fragmentEntryLinkLocalService);
		_setField(_application, "_layoutLocalService", _layoutLocalService);
	}

	@After
	public void tearDown() {
		PermissionThreadLocal.setPermissionChecker(null);
		PropsUtil.set("feature.flag.LPD-99955", "false");
		PropsUtil.set("feature.flag.LPS-178052", "false");
	}

	@Test
	public void testStatusFeatureFlagDisabled() {
		PropsUtil.set("feature.flag.LPD-99955", "false");
		PropsUtil.set("feature.flag.LPS-178052", "false");

		Response response = _application.status();

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"enabled\":false"));
	}

	@Test
	public void testStatusFeatureFlagEnabledViaLpd99955() {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		PropsUtil.set("feature.flag.LPS-178052", "false");

		Response response = _application.status();

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"enabled\":true"));
	}

	@Test
	public void testStatusFeatureFlagEnabledViaLps178052Alias() {
		PropsUtil.set("feature.flag.LPD-99955", "false");
		PropsUtil.set("feature.flag.LPS-178052", "true");

		Response response = _application.status();

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"enabled\":true"));
	}

	@Test
	public void testUpdateFeatureFlagDisabledReturnsForbidden() {
		PropsUtil.set("feature.flag.LPD-99955", "false");
		PropsUtil.set("feature.flag.LPS-178052", "false");

		Response response = _application.updateFragmentEntryLink(_httpServletRequest, 100L, "{\"k\":\"v\"}");

		Assert.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("FeatureDisabled"));
	}

	@Test
	public void testUpdateUnauthenticatedReturnsUnauthorized() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(null);

		Response response = _application.updateFragmentEntryLink(_httpServletRequest, 100L, "{\"k\":\"v\"}");

		Assert.assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("Unauthorized"));
	}

	@Test
	public void testUpdateInvalidParametersReturnsBadRequest() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User user = Mockito.mock(User.class);
		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);

		Response responseZeroId = _application.updateFragmentEntryLink(_httpServletRequest, 0L, "{\"k\":\"v\"}");
		Assert.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), responseZeroId.getStatus());

		Response responseEmptyPayload = _application.updateFragmentEntryLink(_httpServletRequest, 100L, "   ");
		Assert.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), responseEmptyPayload.getStatus());
	}

	@Test
	public void testUpdateEntityNotFoundReturnsNotFound() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User user = Mockito.mock(User.class);
		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(999L)).thenReturn(null);

		Response response = _application.updateFragmentEntryLink(_httpServletRequest, 999L, "{\"k\":\"v\"}");

		Assert.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("NotFound"));
	}

	@Test
	public void testUpdateWithoutPermissionLayoutNotFoundReturnsForbidden() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User user = Mockito.mock(User.class);
		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(user.getCompanyId()).thenReturn(2001L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);

		FragmentEntryLink link = Mockito.mock(FragmentEntryLink.class);
		Mockito.when(link.getGroupId()).thenReturn(3001L);
		Mockito.when(link.getPlid()).thenReturn(4001L);
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(101L)).thenReturn(link);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(false);
		Mockito.when(permissionChecker.isCompanyAdmin(2001L)).thenReturn(false);
		Mockito.when(permissionChecker.isGroupAdmin(3001L)).thenReturn(false);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Mockito.when(_layoutLocalService.fetchLayout(4001L)).thenReturn(null);

		Response response = _application.updateFragmentEntryLink(_httpServletRequest, 101L, "{\"k\":\"v\"}");

		Assert.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("Forbidden"));
	}

	@Test
	public void testUpdateWithoutPermissionLayoutPermissionDeniedReturnsForbidden() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User user = Mockito.mock(User.class);
		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(user.getCompanyId()).thenReturn(2001L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);

		FragmentEntryLink link = Mockito.mock(FragmentEntryLink.class);
		Mockito.when(link.getGroupId()).thenReturn(3001L);
		Mockito.when(link.getPlid()).thenReturn(4001L);
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(101L)).thenReturn(link);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(false);
		Mockito.when(permissionChecker.isCompanyAdmin(2001L)).thenReturn(false);
		Mockito.when(permissionChecker.isGroupAdmin(3001L)).thenReturn(false);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Layout layout = Mockito.mock(Layout.class);
		Mockito.when(_layoutLocalService.fetchLayout(4001L)).thenReturn(layout);
		Mockito.when(_layoutPermission.contains(permissionChecker, layout, ActionKeys.UPDATE)).thenReturn(false);

		Response response = _application.updateFragmentEntryLink(_httpServletRequest, 101L, "{\"k\":\"v\"}");

		Assert.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("Forbidden"));
		Mockito.verify(_layoutPermission).contains(permissionChecker, layout, ActionKeys.UPDATE);
	}

	@Test
	public void testUpdateSuccessAttributedToCaller() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User caller = Mockito.mock(User.class);
		Mockito.when(caller.isDefaultUser()).thenReturn(false);
		Mockito.when(caller.getUserId()).thenReturn(55555L);
		Mockito.when(caller.getCompanyId()).thenReturn(2001L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(caller);

		FragmentEntryLink link = Mockito.mock(FragmentEntryLink.class);
		Mockito.when(link.getUserId()).thenReturn(11111L); // original creator
		Mockito.when(link.getGroupId()).thenReturn(3001L);
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(101L)).thenReturn(link);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _application.updateFragmentEntryLink(_httpServletRequest, 101L, "{\"key\":\"updated\"}");

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"userId\":55555"));

		// Verify service was called with caller's userId (55555), NOT original creator's (11111)
		Mockito.verify(_fragmentEntryLinkLocalService).updateFragmentEntryLink(55555L, 101L, "{\"key\":\"updated\"}", true);
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

	private void _setField(Object target, String fieldName, Object value) throws Exception {
		Class<?> clazz = target.getClass();
		Field field = null;

		while (clazz != null) {
			try {
				field = clazz.getDeclaredField(fieldName);
				break;
			}
			catch (NoSuchFieldException nsfe) {
				clazz = clazz.getSuperclass();
			}
		}

		if (field == null) {
			throw new NoSuchFieldException(fieldName);
		}

		field.setAccessible(true);
		field.set(target, value);
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

	private FragmentOverrideApplication _application;
	private FragmentEntryLinkLocalService _fragmentEntryLinkLocalService;
	private HttpServletRequest _httpServletRequest;
	private LayoutLocalService _layoutLocalService;
	private LayoutPermission _layoutPermission;
	private Portal _portal;

}


