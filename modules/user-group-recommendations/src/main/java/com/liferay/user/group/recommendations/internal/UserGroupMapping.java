package com.liferay.user.group.recommendations.internal;

import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.UserGroup;
import com.liferay.portal.kernel.service.UserGroupLocalService;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns configuration lines into an ordered, de-duplicated list of entry
 * references for the groups a user belongs to.
 *
 * <p>
 * Shared by the legacy Blogs provider and the object-backed one, because the
 * mapping semantics are identical and only the resolution of a reference to an
 * actual item differs.
 * </p>
 *
 * @author Peter Richards
 */
public class UserGroupMapping {

	public static final String STRATEGY_UNION = "union";

	/**
	 * References configured for the groups this user belongs to, in
	 * configuration order.
	 *
	 * <p>
	 * Configuration order is authoritative rather than the order
	 * {@code getUserUserGroups} happens to return, so that "first match" means
	 * the first group an administrator listed, which is something they can see
	 * and control.
	 * </p>
	 *
	 * @param  lines each {@code <user group name>=<ref>,<ref>}, optionally
	 *         prefixed {@code <scope>|} where a scope is in use
	 * @param  scope the prefix to require, or {@code null} to require none
	 */
	public static Set<String> getReferences(
		String[] lines, String scope, long userId, String multiGroupStrategy,
		UserGroupLocalService userGroupLocalService) {

		if ((lines == null) || (lines.length == 0)) {
			return Collections.emptySet();
		}

		if (userId <= 0) {

			// A guest, or a context with no resolvable user. Saying so matters:
			// this returned silently before and was indistinguishable from a
			// user who simply matched no configured group.

			if (_log.isInfoEnabled()) {
				_log.info(
					"No signed-in user, so no recommendations apply");
			}

			return Collections.emptySet();
		}

		Set<String> userGroupNames = new LinkedHashSet<>();

		List<UserGroup> userGroups = userGroupLocalService.getUserUserGroups(
			userId);

		for (UserGroup userGroup : userGroups) {
			userGroupNames.add(StringUtil.toLowerCase(userGroup.getName()));
		}

		if (userGroupNames.isEmpty()) {
			if (_log.isInfoEnabled()) {
				_log.info(
					"User " + userId + " belongs to no user groups, so no " +
						"recommendations apply");
			}

			return Collections.emptySet();
		}

		boolean union = STRATEGY_UNION.equals(multiGroupStrategy);

		// A LinkedHashSet so a union keeps configuration order and never
		// repeats an entry that two groups both name.

		Set<String> references = new LinkedHashSet<>();

		for (String line : lines) {
			if (Validator.isNull(line)) {
				continue;
			}

			String remainder = line.trim();

			if (scope != null) {
				int pipe = remainder.indexOf('|');

				if (pipe <= 0) {
					_log.error(
						"Ignoring line without a scope prefix, expected " +
							"<scope>|<user group name>=<ref>,<ref>: " + line);

					continue;
				}

				if (!StringUtil.equalsIgnoreCase(
						remainder.substring(0, pipe).trim(), scope)) {

					continue;
				}

				remainder = remainder.substring(pipe + 1);
			}

			int equals = remainder.indexOf('=');

			if (equals <= 0) {
				_log.error(
					"Ignoring malformed line, expected " +
						"<user group name>=<ref>,<ref>: " + line);

				continue;
			}

			String userGroupName = StringUtil.toLowerCase(
				remainder.substring(0, equals).trim());

			if (!userGroupNames.contains(userGroupName)) {
				continue;
			}

			if (_log.isInfoEnabled()) {
				_log.info(
					"User " + userId + " matched configured user group \"" +
						userGroupName + "\"");
			}

			for (String reference : remainder.substring(equals + 1).split(",")) {
				reference = reference.trim();

				if (!reference.isEmpty()) {
					references.add(reference);
				}
			}

			if (!union) {
				break;
			}
		}

		return references;
	}

	private static final Log _log = LogFactoryUtil.getLog(
		UserGroupMapping.class);

}
