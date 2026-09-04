package com.liferay.fragment.override.application;

import com.liferay.fragment.model.FragmentEntryLink;
import com.liferay.fragment.service.FragmentEntryLinkLocalService;
import com.liferay.portal.kernel.json.JSONException;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
		Mockito.when(jsonFactory.createJSONObject()).thenAnswer(invocation -> _createMockJSONObject(new LinkedHashMap<>()));
		Mockito.when(jsonFactory.createJSONObject(Mockito.anyString())).thenAnswer(invocation -> {
			String jsonStr = invocation.getArgument(0);
			if (jsonStr == null || jsonStr.trim().isEmpty()) {
				return _createMockJSONObject(new LinkedHashMap<>());
			}
			return _parseMockJSON(jsonStr);
		});
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

	@Test
	public void testUpdateDeepMergePartialOverridesOverExisting() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User caller = Mockito.mock(User.class);
		Mockito.when(caller.isDefaultUser()).thenReturn(false);
		Mockito.when(caller.getUserId()).thenReturn(55555L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(caller);

		FragmentEntryLink link = Mockito.mock(FragmentEntryLink.class);
		Mockito.when(link.getGroupId()).thenReturn(3001L);
		Mockito.when(link.getEditableValues()).thenReturn(
			"{\"title\":{\"value\":\"Original\"},\"endpoint\":\"http://old\",\"active\":true}");
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(101L)).thenReturn(link);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _application.updateFragmentEntryLink(
			_httpServletRequest, 101L, "{\"endpoint\":\"http://new\"}");

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Mockito.verify(_fragmentEntryLinkLocalService).updateFragmentEntryLink(
			55555L, 101L,
			"{\"title\":{\"value\":\"Original\"},\"endpoint\":\"http://new\",\"active\":true}",
			true);
	}

	@Test
	public void testUpdateDeepMergeNestedObjects() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User caller = Mockito.mock(User.class);
		Mockito.when(caller.isDefaultUser()).thenReturn(false);
		Mockito.when(caller.getUserId()).thenReturn(55555L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(caller);

		FragmentEntryLink link = Mockito.mock(FragmentEntryLink.class);
		Mockito.when(link.getGroupId()).thenReturn(3001L);
		Mockito.when(link.getEditableValues()).thenReturn(
			"{\"banner\":{\"img\":\"/old.png\",\"alt\":\"Old alt\"}}");
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(101L)).thenReturn(link);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _application.updateFragmentEntryLink(
			_httpServletRequest, 101L, "{\"banner\":{\"alt\":\"New alt\"}}");

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Mockito.verify(_fragmentEntryLinkLocalService).updateFragmentEntryLink(
			55555L, 101L,
			"{\"banner\":{\"img\":\"/old.png\",\"alt\":\"New alt\"}}",
			true);
	}

	@Test
	public void testUpdateDeepMergeWhenExistingValuesEmptyOrNull() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User caller = Mockito.mock(User.class);
		Mockito.when(caller.isDefaultUser()).thenReturn(false);
		Mockito.when(caller.getUserId()).thenReturn(55555L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(caller);

		FragmentEntryLink link = Mockito.mock(FragmentEntryLink.class);
		Mockito.when(link.getGroupId()).thenReturn(3001L);
		Mockito.when(link.getEditableValues()).thenReturn(null);
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(101L)).thenReturn(link);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _application.updateFragmentEntryLink(
			_httpServletRequest, 101L, "{\"key\":\"new\"}");

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Mockito.verify(_fragmentEntryLinkLocalService).updateFragmentEntryLink(
			55555L, 101L, "{\"key\":\"new\"}", true);
	}

	@Test
	public void testUpdateCorruptedExistingEditableValuesReturnsConflict() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User caller = Mockito.mock(User.class);
		Mockito.when(caller.isDefaultUser()).thenReturn(false);
		Mockito.when(caller.getUserId()).thenReturn(55555L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(caller);

		FragmentEntryLink link = Mockito.mock(FragmentEntryLink.class);
		Mockito.when(link.getGroupId()).thenReturn(3001L);
		Mockito.when(link.getEditableValues()).thenReturn("{corrupted json not valid:");
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(101L)).thenReturn(link);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _application.updateFragmentEntryLink(
			_httpServletRequest, 101L, "{\"endpoint\":\"http://new\"}");

		Assert.assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("CorruptedState"));

		// Verify service was NEVER called, guaranteeing the existing row was not overwritten
		Mockito.verify(_fragmentEntryLinkLocalService, Mockito.never()).updateFragmentEntryLink(
			Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyBoolean());
	}

	@Test
	public void testUpdateInvalidJsonPayloadReturnsBadRequest() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		Response response = _application.updateFragmentEntryLink(
			_httpServletRequest, 101L, "invalid-json");

		Assert.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("BadRequest"));
	}

	@Test
	public void testPatchFragmentEntryLinkSuccess() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User caller = Mockito.mock(User.class);
		Mockito.when(caller.isDefaultUser()).thenReturn(false);
		Mockito.when(caller.getUserId()).thenReturn(55555L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(caller);

		FragmentEntryLink link = Mockito.mock(FragmentEntryLink.class);
		Mockito.when(link.getGroupId()).thenReturn(3001L);
		Mockito.when(link.getEditableValues()).thenReturn("{\"k1\":\"v1\"}");
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(101L)).thenReturn(link);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _application.patchFragmentEntryLink(
			_httpServletRequest, 101L, "{\"k2\":\"v2\"}");

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Mockito.verify(_fragmentEntryLinkLocalService).updateFragmentEntryLink(
			55555L, 101L, "{\"k1\":\"v1\",\"k2\":\"v2\"}", true);
	}

	@Test
	public void testGetFragmentEntryLinkSuccess() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User caller = Mockito.mock(User.class);
		Mockito.when(caller.isDefaultUser()).thenReturn(false);
		Mockito.when(caller.getUserId()).thenReturn(55555L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(caller);

		FragmentEntryLink link = Mockito.mock(FragmentEntryLink.class);
		Mockito.when(link.getFragmentEntryLinkId()).thenReturn(101L);
		Mockito.when(link.getGroupId()).thenReturn(3001L);
		Mockito.when(link.getPlid()).thenReturn(4001L);
		Mockito.when(link.getEditableValues()).thenReturn("{\"endpoint\":\"http://api\"}");
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(101L)).thenReturn(link);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _application.getFragmentEntryLink(_httpServletRequest, 101L);

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Assert.assertTrue(response.getEntity().toString().contains("\"endpoint\":\"http://api\""));
	}

	@Test
	public void testGetFragmentEntryLinkNotFound() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User caller = Mockito.mock(User.class);
		Mockito.when(caller.isDefaultUser()).thenReturn(false);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(caller);
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(999L)).thenReturn(null);

		Response response = _application.getFragmentEntryLink(_httpServletRequest, 999L);

		Assert.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
	}

	@Test
	public void testGetFragmentEntryLinkForbidden() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User caller = Mockito.mock(User.class);
		Mockito.when(caller.isDefaultUser()).thenReturn(false);
		Mockito.when(caller.getCompanyId()).thenReturn(2001L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(caller);

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

		Response response = _application.getFragmentEntryLink(_httpServletRequest, 101L);

		Assert.assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
	}

	@Test
	public void testUpdateReplacesArraysWholesale() throws Exception {
		PropsUtil.set("feature.flag.LPD-99955", "true");
		User caller = Mockito.mock(User.class);
		Mockito.when(caller.isDefaultUser()).thenReturn(false);
		Mockito.when(caller.getUserId()).thenReturn(55555L);
		Mockito.when(_portal.getUser(_httpServletRequest)).thenReturn(caller);

		FragmentEntryLink link = Mockito.mock(FragmentEntryLink.class);
		Mockito.when(link.getGroupId()).thenReturn(3001L);
		Mockito.when(link.getEditableValues()).thenReturn("{\"items\":[1,2]}");
		Mockito.when(_fragmentEntryLinkLocalService.fetchFragmentEntryLink(101L)).thenReturn(link);

		PermissionChecker permissionChecker = Mockito.mock(PermissionChecker.class);
		Mockito.when(permissionChecker.isOmniadmin()).thenReturn(true);
		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		Response response = _application.updateFragmentEntryLink(
			_httpServletRequest, 101L, "{\"items\":[3,4,5]}");

		Assert.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
		Mockito.verify(_fragmentEntryLinkLocalService).updateFragmentEntryLink(
			55555L, 101L, "{\"items\":[3,4,5]}", true);
	}

	private JSONObject _parseMockJSON(String jsonStr) throws JSONException {
		return new SimpleJSONParser(jsonStr).parseObjectMock();
	}

	private JSONObject _createMockJSONObject(Map<String, Object> map) {
		JSONObject jsonObject = Mockito.mock(JSONObject.class);

		Mockito.doAnswer(invocation -> {
			map.put(invocation.getArgument(0), invocation.getArgument(1));
			return jsonObject;
		}).when(jsonObject).put(Mockito.anyString(), Mockito.any(Object.class));

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
		}).when(jsonObject).put(Mockito.anyString(), Mockito.anyDouble());

		Mockito.doAnswer(invocation -> {
			map.put(invocation.getArgument(0), invocation.getArgument(1));
			return jsonObject;
		}).when(jsonObject).put(Mockito.anyString(), Mockito.anyInt());

		Mockito.doAnswer(invocation -> {
			map.put(invocation.getArgument(0), invocation.getArgument(1));
			return jsonObject;
		}).when(jsonObject).put(Mockito.anyString(), Mockito.any(JSONObject.class));

		Mockito.when(jsonObject.keySet()).thenAnswer(invocation -> map.keySet());
		Mockito.when(jsonObject.keys()).thenAnswer(invocation -> map.keySet().iterator());

		Mockito.when(jsonObject.get(Mockito.anyString())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			Object val = map.get(key);
			if (val instanceof Map) {
				JSONObject child = _createMockJSONObject((Map<String, Object>) val);
				map.put(key, child);
				return child;
			}
			return val;
		});

		Mockito.when(jsonObject.getString(Mockito.anyString())).thenAnswer(invocation -> {
			Object val = map.get(invocation.getArgument(0));
			return val != null ? String.valueOf(val) : null;
		});

		Mockito.when(jsonObject.toString()).thenAnswer(invocation -> _serializeJSON(map));

		return jsonObject;
	}

	private String _serializeJSON(Object obj) {
		if (obj == null) {
			return "null";
		}
		if (obj instanceof String) {
			return "\"" + obj + "\"";
		}
		if (obj instanceof Number || obj instanceof Boolean) {
			return String.valueOf(obj);
		}
		if (obj instanceof JSONObject) {
			return obj.toString();
		}
		if (obj instanceof Map) {
			StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
				if (!first) {
					sb.append(",");
				}
				sb.append("\"").append(entry.getKey()).append("\":");
				sb.append(_serializeJSON(entry.getValue()));
				first = false;
			}
			sb.append("}");
			return sb.toString();
		}
		if (obj instanceof List) {
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			for (Object item : (List<?>) obj) {
				if (!first) {
					sb.append(",");
				}
				sb.append(_serializeJSON(item));
				first = false;
			}
			sb.append("]");
			return sb.toString();
		}
		return "\"" + obj.toString() + "\"";
	}

	private static class SimpleJSONParser {
		private final String src;
		private int pos = 0;

		SimpleJSONParser(String src) {
			this.src = src.trim();
		}

		JSONObject parseObjectMock() throws JSONException {
			skipWhitespace();
			if (pos >= src.length()) {
				throw new JSONException("Empty input");
			}
			if (src.charAt(pos) != '{') {
				throw new JSONException("Expected '{' at start of object");
			}
			Map<String, Object> map = parseObject();
			JSONObject mock = Mockito.mock(JSONObject.class);
			return buildMock(map);
		}

		private JSONObject buildMock(Map<String, Object> map) {
			JSONObject jsonObject = Mockito.mock(JSONObject.class);

			Mockito.doAnswer(invocation -> {
				map.put(invocation.getArgument(0), invocation.getArgument(1));
				return jsonObject;
			}).when(jsonObject).put(Mockito.anyString(), Mockito.any(Object.class));

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
			}).when(jsonObject).put(Mockito.anyString(), Mockito.anyDouble());

			Mockito.doAnswer(invocation -> {
				map.put(invocation.getArgument(0), invocation.getArgument(1));
				return jsonObject;
			}).when(jsonObject).put(Mockito.anyString(), Mockito.anyInt());

			Mockito.doAnswer(invocation -> {
				map.put(invocation.getArgument(0), invocation.getArgument(1));
				return jsonObject;
			}).when(jsonObject).put(Mockito.anyString(), Mockito.any(JSONObject.class));

			Mockito.when(jsonObject.keySet()).thenAnswer(invocation -> map.keySet());
			Mockito.when(jsonObject.keys()).thenAnswer(invocation -> map.keySet().iterator());

			Mockito.when(jsonObject.get(Mockito.anyString())).thenAnswer(invocation -> {
				String key = invocation.getArgument(0);
				Object val = map.get(key);
				if (val instanceof Map) {
					JSONObject child = buildMock((Map<String, Object>) val);
					map.put(key, child);
					return child;
				}
				return val;
			});

			Mockito.when(jsonObject.getString(Mockito.anyString())).thenAnswer(invocation -> {
				Object val = map.get(invocation.getArgument(0));
				return val != null ? String.valueOf(val) : null;
			});

			Mockito.when(jsonObject.toString()).thenAnswer(invocation -> serialize(map));

			return jsonObject;
		}

		private String serialize(Object obj) {
			if (obj == null) return "null";
			if (obj instanceof String) return "\"" + obj + "\"";
			if (obj instanceof Number || obj instanceof Boolean) return String.valueOf(obj);
			if (obj instanceof JSONObject) return obj.toString();
			if (obj instanceof Map) {
				StringBuilder sb = new StringBuilder("{");
				boolean first = true;
				for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
					if (!first) sb.append(",");
					sb.append("\"").append(entry.getKey()).append("\":");
					sb.append(serialize(entry.getValue()));
					first = false;
				}
				sb.append("}");
				return sb.toString();
			}
			if (obj instanceof List) {
				StringBuilder sb = new StringBuilder("[");
				boolean first = true;
				for (Object item : (List<?>) obj) {
					if (!first) sb.append(",");
					sb.append(serialize(item));
					first = false;
				}
				sb.append("]");
				return sb.toString();
			}
			return "\"" + obj.toString() + "\"";
		}

		private Object parseValue() throws JSONException {
			skipWhitespace();
			if (pos >= src.length()) {
				throw new JSONException("Unexpected end of input");
			}
			char c = src.charAt(pos);
			if (c == '{') return parseObject();
			if (c == '[') return parseArray();
			if (c == '"') return parseString();
			if (c == 't' || c == 'f') return parseBoolean();
			if (c == 'n') return parseNull();
			if (Character.isDigit(c) || c == '-') return parseNumber();
			throw new JSONException("Unexpected character at " + pos + ": " + c);
		}

		private void skipWhitespace() {
			while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
				pos++;
			}
		}

		private Map<String, Object> parseObject() throws JSONException {
			Map<String, Object> map = new LinkedHashMap<>();
			pos++; // skip '{'
			skipWhitespace();
			if (pos < src.length() && src.charAt(pos) == '}') {
				pos++;
				return map;
			}
			while (pos < src.length()) {
				skipWhitespace();
				if (src.charAt(pos) != '"') {
					throw new JSONException("Expected '\"' for key at " + pos);
				}
				String key = parseString();
				skipWhitespace();
				if (pos >= src.length() || src.charAt(pos) != ':') {
					throw new JSONException("Expected ':' after key at " + pos);
				}
				pos++; // skip ':'
				skipWhitespace();
				Object val = parseValue();
				map.put(key, val);
				skipWhitespace();
				if (pos < src.length() && src.charAt(pos) == ',') {
					pos++;
				}
				else if (pos < src.length() && src.charAt(pos) == '}') {
					pos++;
					return map;
				}
				else {
					throw new JSONException("Expected ',' or '}' at " + pos);
				}
			}
			throw new JSONException("Unterminated object");
		}

		private List<Object> parseArray() throws JSONException {
			List<Object> list = new ArrayList<>();
			pos++; // skip '['
			skipWhitespace();
			if (pos < src.length() && src.charAt(pos) == ']') {
				pos++;
				return list;
			}
			while (pos < src.length()) {
				skipWhitespace();
				Object val = parseValue();
				list.add(val);
				skipWhitespace();
				if (pos < src.length() && src.charAt(pos) == ',') {
					pos++;
				}
				else if (pos < src.length() && src.charAt(pos) == ']') {
					pos++;
					return list;
				}
				else {
					throw new JSONException("Expected ',' or ']' at " + pos);
				}
			}
			throw new JSONException("Unterminated array");
		}

		private String parseString() throws JSONException {
			pos++; // skip '"'
			StringBuilder sb = new StringBuilder();
			while (pos < src.length()) {
				char c = src.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					if (pos >= src.length()) throw new JSONException("Invalid escape");
					char esc = src.charAt(pos++);
					if (esc == '"') sb.append('"');
					else if (esc == '\\') sb.append('\\');
					else if (esc == '/') sb.append('/');
					else if (esc == 'b') sb.append('\b');
					else if (esc == 'f') sb.append('\f');
					else if (esc == 'n') sb.append('\n');
					else if (esc == 'r') sb.append('\r');
					else if (esc == 't') sb.append('\t');
					else sb.append(esc);
				}
				else {
					sb.append(c);
				}
			}
			throw new JSONException("Unterminated string");
		}

		private Boolean parseBoolean() throws JSONException {
			if (src.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (src.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			throw new JSONException("Invalid boolean at " + pos);
		}

		private Object parseNull() throws JSONException {
			if (src.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			throw new JSONException("Invalid null at " + pos);
		}

		private Number parseNumber() {
			int start = pos;
			if (src.charAt(pos) == '-') pos++;
			while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
			boolean isFloating = false;
			if (pos < src.length() && src.charAt(pos) == '.') {
				isFloating = true;
				pos++;
				while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
			}
			String numStr = src.substring(start, pos);
			if (isFloating) {
				return Double.valueOf(numStr);
			}
			try {
				return Long.valueOf(numStr);
			} catch (NumberFormatException e) {
				return Double.valueOf(numStr);
			}
		}
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


