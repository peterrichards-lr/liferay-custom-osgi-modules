package com.liferay.user.group.recommendations.internal;

import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.security.auth.PrincipalThreadLocal;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.theme.ThemeDisplay;

/**
 * Resolves the user a collection is being rendered for.
 *
 * <p>
 * <b>Why this is not just {@code ServiceContext#getUserId}.</b> It was, and it
 * returned zero. A {@code ServiceContext} on the thread-local during a render
 * carries the company and scope group -- which is all Liferay's own collection
 * providers ever read from it -- but not necessarily the user. Everything
 * downstream then behaved correctly for a user in no groups: an empty
 * collection, no error, and no way to tell it apart from a genuine
 * no-matching-group result.
 * </p>
 *
 * <p>
 * The permission checker is the reliable source during a request, so it is
 * tried first, then the principal thread local, then the theme display, and
 * only then the service context.
 * </p>
 *
 * @author Peter Richards
 */
public class CurrentUserUtil {

	/**
	 * The signed-in user's id, or {@code 0} for a guest or when no user can be
	 * determined.
	 */
	public static long getUserId(ServiceContext serviceContext) {
		PermissionChecker permissionChecker =
			PermissionThreadLocal.getPermissionChecker();

		if ((permissionChecker != null) && permissionChecker.isSignedIn()) {
			return permissionChecker.getUserId();
		}

		long userId = PrincipalThreadLocal.getUserId();

		if (userId > 0) {
			return userId;
		}

		if (serviceContext != null) {
			ThemeDisplay themeDisplay = serviceContext.getThemeDisplay();

			if ((themeDisplay != null) && themeDisplay.isSignedIn()) {
				return themeDisplay.getUserId();
			}

			if (serviceContext.getUserId() > 0) {
				return serviceContext.getUserId();
			}
		}

		if (_log.isDebugEnabled()) {
			_log.debug(
				"No signed-in user could be resolved, so no user group " +
					"recommendations apply");
		}

		return 0;
	}

	private static final Log _log = LogFactoryUtil.getLog(
		CurrentUserUtil.class);

}
