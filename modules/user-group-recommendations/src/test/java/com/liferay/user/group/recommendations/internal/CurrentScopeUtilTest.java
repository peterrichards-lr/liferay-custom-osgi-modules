package com.liferay.user.group.recommendations.internal;

import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.theme.ThemeDisplay;

import org.junit.Assert;
import org.mockito.Mockito;
import org.junit.Test;

/**
 * Covers resolving the scope group for a render.
 *
 * <p>
 * This exists for the same reason {@link CurrentUserUtilTest} does: the
 * {@code ServiceContext} on the thread local during a render returned zero for
 * the user id on a live portal, and cannot be trusted for the scope group
 * either. Querying group zero finds nothing, silently, which looks exactly like
 * a site with no content.
 * </p>
 *
 * @author Peter Richards
 */
public class CurrentScopeUtilTest {

	@Test
	public void testFallsBackToServiceContextWhenThereIsNoThemeDisplay() {
		ServiceContext serviceContext = new ServiceContext();

		serviceContext.setScopeGroupId(_SITE_GROUP_ID);

		Assert.assertEquals(
			_SITE_GROUP_ID, CurrentScopeUtil.getScopeGroupId(serviceContext));
	}

	@Test
	public void testPrefersTheThemeDisplay() {
		// ServiceContext derives its theme display from the request, so this
		// is mocked rather than constructed.

		ThemeDisplay themeDisplay = new ThemeDisplay();

		themeDisplay.setScopeGroupId(_SITE_GROUP_ID);

		ServiceContext serviceContext = Mockito.mock(ServiceContext.class);

		Mockito.when(
			serviceContext.getThemeDisplay()
		).thenReturn(
			themeDisplay
		);

		// The regression: a ServiceContext carrying no scope group must not
		// win over a theme display that has one.

		Mockito.when(serviceContext.getScopeGroupId()).thenReturn(0L);

		Assert.assertEquals(
			_SITE_GROUP_ID, CurrentScopeUtil.getScopeGroupId(serviceContext));
	}

	@Test
	public void testReturnsZeroForANullServiceContext() {
		Assert.assertEquals(0, CurrentScopeUtil.getScopeGroupId(null));
	}

	@Test
	public void testReturnsZeroWhenNeitherSourceHasAGroup() {
		Assert.assertEquals(
			0, CurrentScopeUtil.getScopeGroupId(new ServiceContext()));
	}

	private static final long _SITE_GROUP_ID = 20123L;

}
