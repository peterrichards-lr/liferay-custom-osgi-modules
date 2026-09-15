package com.liferay.user.group.recommendations.internal;

import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.service.ServiceContext;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Covers resolving the user a collection is rendered for.
 *
 * <p>
 * This exists because {@code ServiceContext#getUserId} returned zero during a
 * render on a live portal. Everything downstream then behaved exactly as it
 * would for a user in no groups -- an empty collection, silently -- which was
 * indistinguishable from a genuine no-matching-group result and took a long
 * time to find.
 * </p>
 *
 * @author Peter Richards
 */
public class CurrentUserUtilTest {

	@After
	public void tearDown() {
		PermissionThreadLocal.setPermissionChecker(null);
	}

	@Test
	public void testFallsBackToServiceContextWhenNothingElseHasAUser() {
		PermissionThreadLocal.setPermissionChecker(null);

		ServiceContext serviceContext = new ServiceContext();

		serviceContext.setUserId(_USER_ID);

		Assert.assertEquals(
			_USER_ID, CurrentUserUtil.getUserId(serviceContext));
	}

	@Test
	public void testGuestPermissionCheckerFallsThroughRatherThanReturningGuest() {
		PermissionChecker permissionChecker = Mockito.mock(
			PermissionChecker.class);

		Mockito.when(permissionChecker.isSignedIn()).thenReturn(false);
		Mockito.when(permissionChecker.getUserId()).thenReturn(_GUEST_ID);

		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		// A guest must not be treated as a signed-in user whose groups happen
		// to be empty; it should fall through and end at zero.

		Assert.assertEquals(0, CurrentUserUtil.getUserId(new ServiceContext()));
	}

	@Test
	public void testPrefersThePermissionChecker() {
		PermissionChecker permissionChecker = Mockito.mock(
			PermissionChecker.class);

		Mockito.when(permissionChecker.isSignedIn()).thenReturn(true);
		Mockito.when(permissionChecker.getUserId()).thenReturn(_USER_ID);

		PermissionThreadLocal.setPermissionChecker(permissionChecker);

		ServiceContext serviceContext = new ServiceContext();

		// The regression: a ServiceContext carrying no user must not win.

		serviceContext.setUserId(0);

		Assert.assertEquals(
			_USER_ID, CurrentUserUtil.getUserId(serviceContext));
	}

	@Test
	public void testReturnsZeroWhenNothingCanResolveAUser() {
		PermissionThreadLocal.setPermissionChecker(null);

		Assert.assertEquals(0, CurrentUserUtil.getUserId(new ServiceContext()));
	}

	@Test
	public void testToleratesANullServiceContext() {
		PermissionThreadLocal.setPermissionChecker(null);

		Assert.assertEquals(0, CurrentUserUtil.getUserId(null));
	}

	private static final long _GUEST_ID = 20125L;

	private static final long _USER_ID = 42L;

}
