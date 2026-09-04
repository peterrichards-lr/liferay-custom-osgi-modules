package com.liferay.search.reindex;

import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.search.IndexWriterHelper;
import com.liferay.portal.kernel.search.IndexWriterHelperUtil;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.kernel.util.PortalUtil;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class SearchReindexResourceTest {

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

		_mockIndexWriterHelper = Mockito.mock(IndexWriterHelper.class);
		_setIndexWriterHelperSupplier(() -> _mockIndexWriterHelper);

		_httpServletRequest = Mockito.mock(HttpServletRequest.class);
		_resource = new SearchReindexResource();
	}

	@After
	public void tearDown() {
		PermissionThreadLocal.setPermissionChecker(null);
	}

	@Test
	public void testStatusReturnsOk() {
		Response response = _resource.status();

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"status\":\"active\""));
		Assert.assertTrue(response.getEntity().toString().contains("\"module\":\"search-reindex\""));
	}

	@Test
	public void testReindexAllUnauthenticatedReturnsUnauthorized() throws Exception {
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(null);

		Response response = _resource.reindexAll(_httpServletRequest);

		Assert.assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("Unauthorized"));
	}

	@Test
	public void testReindexAllNonOmniadminReturnsForbidden() throws Exception {
		User user = Mockito.mock(User.class);
		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(false);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _resource.reindexAll(_httpServletRequest);

		Assert.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("Forbidden"));
	}

	@Test
	public void testReindexAllOmniadminSuccess() throws Exception {
		User user = Mockito.mock(User.class);
		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Mockito.when(_portal.getCompanyIds()).thenReturn(new long[]{2001L, 2002L});

		Response response = _resource.reindexAll(_httpServletRequest);

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"status\":\"success\""));
		Assert.assertTrue(response.getEntity().toString().contains("\"companyCount\":2"));

		Mockito.verify(_mockIndexWriterHelper).reindex(Mockito.eq(0L), Mockito.eq("reindex"), Mockito.eq(new long[]{2001L}), Mockito.isNull());
		Mockito.verify(_mockIndexWriterHelper).reindex(Mockito.eq(0L), Mockito.eq("reindex"), Mockito.eq(new long[]{2002L}), Mockito.isNull());
	}

	@Test
	public void testReindexClassUnauthenticatedReturnsUnauthorized() throws Exception {
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(null);

		Response response = _resource.reindexClass(_httpServletRequest, "com.liferay.journal.model.JournalArticle");

		Assert.assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("Unauthorized"));
	}

	@Test
	public void testReindexClassNonOmniadminReturnsForbidden() throws Exception {
		User user = Mockito.mock(User.class);
		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(false);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _resource.reindexClass(_httpServletRequest, "com.liferay.journal.model.JournalArticle");

		Assert.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("Forbidden"));
	}

	@Test
	public void testReindexClassBlankNameReturnsBadRequest() {
		Response responseNull = _resource.reindexClass(_httpServletRequest, null);
		Assert.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), responseNull.getStatus());

		Response responseBlank = _resource.reindexClass(_httpServletRequest, "   ");
		Assert.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), responseBlank.getStatus());
	}

	@Test
	public void testReindexClassOmniadminSuccess() throws Exception {
		User user = Mockito.mock(User.class);
		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Mockito.when(_portal.getCompanyIds()).thenReturn(new long[]{2001L});

		String className = "com.liferay.commerce.product.model.CPDefinition";
		Response response = _resource.reindexClass(_httpServletRequest, className);

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"status\":\"success\""));
		Assert.assertTrue(response.getEntity().toString().contains("\"className\":\"" + className + "\""));
		Assert.assertTrue(response.getEntity().toString().contains("\"companyCount\":1"));

		Mockito.verify(_mockIndexWriterHelper).reindex(
			Mockito.eq(0L), Mockito.eq("reindex"), Mockito.eq(new long[]{2001L}),
			Mockito.eq(className), Mockito.isNull());
	}

	@Test
	public void testReindexClassInternalErrorReturnsSanitized500() throws Exception {
		User user = Mockito.mock(User.class);
		Mockito.when(user.isDefaultUser()).thenReturn(false);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(user);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Mockito.when(_portal.getCompanyIds()).thenThrow(new RuntimeException("Database connection dead"));

		Response response = _resource.reindexClass(_httpServletRequest, "com.liferay.journal.model.JournalArticle");

		Assert.assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("InternalServerError"));
		// Assert that raw internal exception message is NOT leaked to caller
		Assert.assertFalse(response.getEntity().toString().contains("Database connection dead"));
	}

	private void _setIndexWriterHelperSupplier(Supplier<IndexWriterHelper> supplier) throws Exception {
		Field snapshotField = IndexWriterHelperUtil.class.getDeclaredField("_indexWriterHelperSnapshot");
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

	private HttpServletRequest _httpServletRequest;
	private IndexWriterHelper _mockIndexWriterHelper;
	private Portal _portal;
	private SearchReindexResource _resource;

}
