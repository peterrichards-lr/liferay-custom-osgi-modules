package com.liferay.user.group.recommendations.internal;

import com.liferay.portal.kernel.model.UserGroup;
import com.liferay.portal.kernel.service.UserGroupLocalService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Covers the mapping logic shared by the legacy Blogs provider and the
 * object-backed one.
 *
 * @author Peter Richards
 */
public class UserGroupMappingTest {

	@Before
	public void setUp() {
		_userGroupLocalService = Mockito.mock(UserGroupLocalService.class);
	}

	@Test
	public void testConfigurationOrderDecidesFirstMatch() {
		_givenUserGroups("Engineering", "Riders");

		// The service returns Engineering first. Configuration lists Riders
		// first, and configuration must win: it is the order an administrator
		// can see and change.

		Assert.assertEquals(
			Arrays.asList("a", "b"),
			_references(
				new String[] {"Riders=a,b", "Engineering=c"}, null,
				"firstMatch"));
	}

	@Test
	public void testEmptyWhenUserBelongsToNoConfiguredGroup() {
		_givenUserGroups("Marketing");

		Assert.assertTrue(
			_references(
				new String[] {"Riders=a"}, null, "firstMatch"
			).isEmpty());
	}

	@Test
	public void testEmptyWhenUserHasNoGroups() {
		_givenUserGroups();

		Assert.assertTrue(
			_references(
				new String[] {"Riders=a"}, null, "firstMatch"
			).isEmpty());
	}

	@Test
	public void testGuestIsNotLookedUp() {
		Set<String> references = UserGroupMapping.getReferences(
			new String[] {"Riders=a"}, null, 0, "firstMatch",
			_userGroupLocalService);

		Assert.assertTrue(references.isEmpty());

		Mockito.verify(
			_userGroupLocalService, Mockito.never()
		).getUserUserGroups(
			Mockito.anyLong()
		);
	}

	@Test
	public void testMalformedLineIsSkippedWithoutLosingTheRest() {
		_givenUserGroups("Riders");

		Assert.assertEquals(
			Arrays.asList("a"),
			_references(
				new String[] {"no equals sign here", "Riders=a"}, null,
				"firstMatch"));
	}

	@Test
	public void testScopePrefixSelectsOnlyMatchingLines() {
		_givenUserGroups("Riders");

		// Two content types configured; only the one this provider serves
		// should contribute.

		Assert.assertEquals(
			Arrays.asList("moto-1", "moto-2"),
			_references(
				new String[] {
					"OtherType|Riders=other-1", "MotorBlog|Riders=moto-1,moto-2"
				},
				"MotorBlog", "firstMatch"));
	}

	@Test
	public void testScopePrefixIsCaseInsensitive() {
		_givenUserGroups("Riders");

		Assert.assertEquals(
			Arrays.asList("a"),
			_references(
				new String[] {"motorblog|Riders=a"}, "MotorBlog",
				"firstMatch"));
	}

	@Test
	public void testScopedLineWithoutPrefixIsSkipped() {
		_givenUserGroups("Riders");

		// A line written for the unscoped setting must not leak into a scoped
		// provider, or a legacy Blogs mapping would be served as object refs.

		Assert.assertTrue(
			_references(
				new String[] {"Riders=a"}, "MotorBlog", "firstMatch"
			).isEmpty());
	}

	@Test
	public void testUnionDeduplicatesAndKeepsConfigurationOrder() {
		_givenUserGroups("Riders", "Engineering");

		Assert.assertEquals(
			Arrays.asList("a", "b", "c"),
			_references(
				new String[] {"Riders=a,b", "Engineering=b,c"}, null, "union"));
	}

	@Test
	public void testUserGroupNameIsMatchedCaseInsensitivelyAndTrimmed() {
		_givenUserGroups("Riders");

		Assert.assertEquals(
			Arrays.asList("a"),
			_references(
				new String[] {"  riders  =a"}, null, "firstMatch"));
	}

	@Test
	public void testWhitespaceAroundReferencesIsStripped() {
		_givenUserGroups("Riders");

		Assert.assertEquals(
			Arrays.asList("a", "b"),
			_references(
				new String[] {"Riders= a , b "}, null, "firstMatch"));
	}

	private void _givenUserGroups(String... names) {
		List<UserGroup> userGroups = new ArrayList<>();

		for (String name : names) {
			UserGroup userGroup = Mockito.mock(UserGroup.class);

			Mockito.when(userGroup.getName()).thenReturn(name);

			userGroups.add(userGroup);
		}

		Mockito.when(
			_userGroupLocalService.getUserUserGroups(_USER_ID)
		).thenReturn(
			userGroups
		);
	}

	private List<String> _references(
		String[] lines, String scope, String multiGroupStrategy) {

		return new ArrayList<>(
			UserGroupMapping.getReferences(
				lines, scope, _USER_ID, multiGroupStrategy,
				_userGroupLocalService));
	}

	private static final long _USER_ID = 42L;

	private UserGroupLocalService _userGroupLocalService;

}
