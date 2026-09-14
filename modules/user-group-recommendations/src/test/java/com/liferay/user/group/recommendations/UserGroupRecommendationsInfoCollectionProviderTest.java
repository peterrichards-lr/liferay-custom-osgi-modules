package com.liferay.user.group.recommendations;

import com.liferay.blogs.model.BlogsEntry;
import com.liferay.blogs.service.BlogsEntryLocalService;
import com.liferay.info.collection.provider.CollectionQuery;
import com.liferay.info.pagination.InfoPage;
import com.liferay.info.pagination.Pagination;
import com.liferay.portal.kernel.dao.orm.QueryDefinition;
import com.liferay.portal.kernel.language.Language;
import com.liferay.portal.kernel.model.UserGroup;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceContextThreadLocal;
import com.liferay.portal.kernel.service.UserGroupLocalService;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * @author Peter Richards
 */
public class UserGroupRecommendationsInfoCollectionProviderTest {

	@Before
	public void setUp() throws Exception {
		_blogsEntryLocalService = Mockito.mock(BlogsEntryLocalService.class);
		_userGroupLocalService = Mockito.mock(UserGroupLocalService.class);
		_language = Mockito.mock(Language.class);

		_infoCollectionProvider =
			new UserGroupRecommendationsInfoCollectionProvider();

		_setField(
			_infoCollectionProvider, "_blogsEntryLocalService",
			_blogsEntryLocalService);
		_setField(
			_infoCollectionProvider, "_userGroupLocalService",
			_userGroupLocalService);
		_setField(_infoCollectionProvider, "_language", _language);

		_entries = new HashMap<>();

		Mockito.when(
			_blogsEntryLocalService.fetchBlogsEntry(Mockito.anyLong())
		).thenAnswer(
			invocation -> _entries.get(invocation.getArgument(0))
		);
	}

	@After
	public void tearDown() {
		ServiceContextThreadLocal.remove();
	}

	@Test
	public void testConfiguredOrderIsPreserved() {
		_pushServiceContext(_USER_ID);
		_givenUserGroups(_GROUP_A);
		_givenEntries(10L, 11L, 12L);

		// Deliberately not ascending: hand-picking is about sequence, so the
		// provider must not re-sort.

		InfoPage<BlogsEntry> infoPage = _getCollectionInfoPage(
			_configuration("12", "10", "11"));

		Assert.assertEquals(Arrays.asList(12L, 10L, 11L), _entryIds(infoPage));
	}

	@Test
	public void testFallbackEmptyWhenUserMatchesNoGroup() {
		_pushServiceContext(_USER_ID);
		_givenUserGroups();

		InfoPage<BlogsEntry> infoPage = _getCollectionInfoPage(
			_configuration("10"));

		Assert.assertTrue(infoPage.getPageItems().isEmpty());
		Assert.assertEquals(0, infoPage.getTotalCount());
	}

	@Test
	public void testFallbackRecentWhenUserMatchesNoGroup() {
		_pushServiceContext(_USER_ID);
		_givenUserGroups();
		_givenEntries(90L, 91L);

		Mockito.when(
			_blogsEntryLocalService.getGroupEntries(
				Mockito.anyLong(), Mockito.<QueryDefinition<BlogsEntry>>any())
		).thenReturn(
			new ArrayList<>(Arrays.asList(_entries.get(90L), _entries.get(91L)))
		);

		Map<String, String[]> configuration = _configuration("10");

		configuration.put(
			UserGroupRecommendationsInfoCollectionProvider.FIELD_FALLBACK,
			new String[] {
				UserGroupRecommendationsInfoCollectionProvider.FALLBACK_RECENT
			});

		InfoPage<BlogsEntry> infoPage = _getCollectionInfoPage(configuration);

		Assert.assertEquals(
			Arrays.asList(90L, 91L), _entryIds(infoPage));
	}

	@Test
	public void testFirstMatchUsesOnlyTheFirstMatchingGroup() {
		_pushServiceContext(_USER_ID);
		_givenUserGroups(_GROUP_A, _GROUP_B);
		_givenEntries(10L, 11L, 20L, 21L);

		Map<String, String[]> configuration = _configuration("10", "11");

		configuration.put(
			UserGroupRecommendationsInfoCollectionProvider.
				USER_GROUP_FIELD_PREFIX + _GROUP_B,
			new String[] {"20", "21"});

		InfoPage<BlogsEntry> infoPage = _getCollectionInfoPage(configuration);

		Assert.assertEquals(Arrays.asList(10L, 11L), _entryIds(infoPage));
	}

	@Test
	public void testGuestFallsBackRatherThanThrowing() {
		_pushServiceContext(0);
		_givenUserGroups(_GROUP_A);

		InfoPage<BlogsEntry> infoPage = _getCollectionInfoPage(
			_configuration("10"));

		Assert.assertTrue(infoPage.getPageItems().isEmpty());

		Mockito.verify(
			_userGroupLocalService, Mockito.never()
		).getUserUserGroups(
			Mockito.anyLong()
		);
	}

	@Test
	public void testKeyAndLabel() {
		Mockito.when(
			_language.get(Mockito.<java.util.ResourceBundle>any(), Mockito.anyString())
		).thenReturn(
			"Recommended for Your Group"
		);

		Assert.assertEquals(
			"userGroupRecommendations", _infoCollectionProvider.getKey());
	}

	@Test
	public void testMissingServiceContextReturnsEmptyRatherThanThrowing() {
		ServiceContextThreadLocal.remove();

		InfoPage<BlogsEntry> infoPage = _getCollectionInfoPage(
			_configuration("10"));

		Assert.assertTrue(infoPage.getPageItems().isEmpty());
		Assert.assertEquals(0, infoPage.getTotalCount());
	}

	@Test
	public void testPaginationSlicesWithoutLosingTotalCount() {
		_pushServiceContext(_USER_ID);
		_givenUserGroups(_GROUP_A);
		_givenEntries(10L, 11L, 12L);

		CollectionQuery collectionQuery = new CollectionQuery();

		collectionQuery.setConfiguration(_configuration("10", "11", "12"));
		collectionQuery.setPagination(Pagination.of(2, 0));

		InfoPage<BlogsEntry> infoPage =
			_infoCollectionProvider.getCollectionInfoPage(collectionQuery);

		Assert.assertEquals(2, infoPage.getPageItems().size());
		Assert.assertEquals(3, infoPage.getTotalCount());
	}

	@Test
	public void testUnapprovedAndDeletedEntriesAreSkipped() {
		_pushServiceContext(_USER_ID);
		_givenUserGroups(_GROUP_A);
		_givenEntries(10L, 12L);

		BlogsEntry draftBlogsEntry = Mockito.mock(BlogsEntry.class);

		Mockito.when(draftBlogsEntry.getEntryId()).thenReturn(11L);
		Mockito.when(
			draftBlogsEntry.getStatus()
		).thenReturn(
			WorkflowConstants.STATUS_DRAFT
		);

		_entries.put(11L, draftBlogsEntry);

		// 99 resolves to null, standing in for an entry deleted after the page
		// was configured.

		InfoPage<BlogsEntry> infoPage = _getCollectionInfoPage(
			_configuration("10", "11", "99", "12"));

		Assert.assertEquals(Arrays.asList(10L, 12L), _entryIds(infoPage));
	}

	@Test
	public void testUnionCombinesAndDeduplicatesAcrossGroups() {
		_pushServiceContext(_USER_ID);
		_givenUserGroups(_GROUP_A, _GROUP_B);
		_givenEntries(10L, 11L, 20L);

		Map<String, String[]> configuration = _configuration("10", "11");

		configuration.put(
			UserGroupRecommendationsInfoCollectionProvider.
				USER_GROUP_FIELD_PREFIX + _GROUP_B,
			new String[] {"11", "20"});
		configuration.put(
			UserGroupRecommendationsInfoCollectionProvider.
				FIELD_MULTI_GROUP_STRATEGY,
			new String[] {
				UserGroupRecommendationsInfoCollectionProvider.STRATEGY_UNION
			});

		InfoPage<BlogsEntry> infoPage = _getCollectionInfoPage(configuration);

		Assert.assertEquals(
			Arrays.asList(10L, 11L, 20L), _entryIds(infoPage));
	}

	private Map<String, String[]> _configuration(String... groupAEntryIds) {
		Map<String, String[]> configuration = new HashMap<>();

		configuration.put(
			UserGroupRecommendationsInfoCollectionProvider.
				USER_GROUP_FIELD_PREFIX + _GROUP_A,
			groupAEntryIds);

		return configuration;
	}

	private List<Long> _entryIds(InfoPage<BlogsEntry> infoPage) {
		List<Long> entryIds = new ArrayList<>();

		for (BlogsEntry blogsEntry : infoPage.getPageItems()) {
			entryIds.add(blogsEntry.getEntryId());
		}

		return entryIds;
	}

	private InfoPage<BlogsEntry> _getCollectionInfoPage(
		Map<String, String[]> configuration) {

		CollectionQuery collectionQuery = new CollectionQuery();

		collectionQuery.setConfiguration(configuration);
		collectionQuery.setPagination(Pagination.of(20, 0));

		return _infoCollectionProvider.getCollectionInfoPage(collectionQuery);
	}

	private void _givenEntries(long... entryIds) {
		for (long entryId : entryIds) {
			BlogsEntry blogsEntry = Mockito.mock(BlogsEntry.class);

			Mockito.when(blogsEntry.getEntryId()).thenReturn(entryId);
			Mockito.when(
				blogsEntry.getStatus()
			).thenReturn(
				WorkflowConstants.STATUS_APPROVED
			);

			_entries.put(entryId, blogsEntry);
		}
	}

	private void _givenUserGroups(long... userGroupIds) {
		List<UserGroup> userGroups = new ArrayList<>();

		for (long userGroupId : userGroupIds) {
			UserGroup userGroup = Mockito.mock(UserGroup.class);

			Mockito.when(userGroup.getUserGroupId()).thenReturn(userGroupId);

			userGroups.add(userGroup);
		}

		Mockito.when(
			_userGroupLocalService.getUserUserGroups(_USER_ID)
		).thenReturn(
			userGroups
		);
	}

	private void _pushServiceContext(long userId) {
		ServiceContext serviceContext = new ServiceContext();

		serviceContext.setCompanyId(_COMPANY_ID);
		serviceContext.setScopeGroupId(_SCOPE_GROUP_ID);
		serviceContext.setUserId(userId);

		ServiceContextThreadLocal.pushServiceContext(serviceContext);
	}

	private void _setField(Object target, String name, Object value)
		throws Exception {

		Field field = target.getClass().getDeclaredField(name);

		field.setAccessible(true);

		field.set(target, value);
	}

	private static final long _COMPANY_ID = 20099L;

	private static final long _GROUP_A = 100L;

	private static final long _GROUP_B = 200L;

	private static final long _SCOPE_GROUP_ID = 20123L;

	private static final long _USER_ID = 42L;

	private BlogsEntryLocalService _blogsEntryLocalService;
	private Map<Long, BlogsEntry> _entries;
	private UserGroupRecommendationsInfoCollectionProvider
		_infoCollectionProvider;
	private Language _language;
	private UserGroupLocalService _userGroupLocalService;

}
