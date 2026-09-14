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
import com.liferay.user.group.recommendations.configuration.UserGroupRecommendationsConfiguration;

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

		_byUrlTitle = new HashMap<>();
		_byExternalReferenceCode = new HashMap<>();
		_byEntryId = new HashMap<>();

		Mockito.when(
			_blogsEntryLocalService.fetchEntry(
				Mockito.anyLong(), Mockito.anyString())
		).thenAnswer(
			invocation -> _byUrlTitle.get(invocation.getArgument(1))
		);

		Mockito.when(
			_blogsEntryLocalService.fetchBlogsEntryByExternalReferenceCode(
				Mockito.anyString(), Mockito.anyLong())
		).thenAnswer(
			invocation -> _byExternalReferenceCode.get(
				invocation.getArgument(0))
		);

		Mockito.when(
			_blogsEntryLocalService.fetchBlogsEntry(Mockito.anyLong())
		).thenAnswer(
			invocation -> _byEntryId.get(invocation.getArgument(0))
		);
	}

	@After
	public void tearDown() {
		ServiceContextThreadLocal.remove();
	}

	@Test
	public void testConfiguredOrderIsPreserved() throws Exception {
		_pushServiceContext(_USER_ID);
		_givenUserGroups("Riders");
		_givenEntries("alpha", "beta", "gamma");

		// Deliberately not alphabetical: hand-picking is about sequence, so the
		// provider must not re-sort.

		_configure("Riders=gamma,alpha,beta");

		Assert.assertEquals(
			Arrays.asList("gamma", "alpha", "beta"),
			_urlTitles(_getCollectionInfoPage()));
	}

	@Test
	public void testFallbackEmptyWhenUserMatchesNoConfiguredGroup()
		throws Exception {

		_pushServiceContext(_USER_ID);
		_givenUserGroups("Marketing");
		_givenEntries("alpha");

		_configure("Riders=alpha");

		InfoPage<BlogsEntry> infoPage = _getCollectionInfoPage();

		Assert.assertTrue(infoPage.getPageItems().isEmpty());
		Assert.assertEquals(0, infoPage.getTotalCount());
	}

	@Test
	public void testFallbackRecent() throws Exception {
		_pushServiceContext(_USER_ID);
		_givenUserGroups("Marketing");
		_givenEntries("recent-one", "recent-two");

		Mockito.when(
			_blogsEntryLocalService.getGroupEntries(
				Mockito.anyLong(), Mockito.<QueryDefinition<BlogsEntry>>any())
		).thenReturn(
			new ArrayList<>(
				Arrays.asList(
					_byUrlTitle.get("recent-one"),
					_byUrlTitle.get("recent-two")))
		);

		_configureWith(
			UserGroupRecommendationsInfoCollectionProvider.
				STRATEGY_FIRST_MATCH,
			UserGroupRecommendationsInfoCollectionProvider.FALLBACK_RECENT,
			"Riders=alpha");

		Assert.assertEquals(
			Arrays.asList("recent-one", "recent-two"),
			_urlTitles(_getCollectionInfoPage()));
	}

	@Test
	public void testFirstMatchFollowsConfigurationOrderNotServiceOrder()
		throws Exception {

		_pushServiceContext(_USER_ID);

		// The service returns Engineering first; configuration lists Riders
		// first. Configuration must win, because that is the order an
		// administrator can see.

		_givenUserGroups("Engineering", "Riders");
		_givenEntries("alpha", "beta", "gamma");

		_configure("Riders=alpha,beta", "Engineering=gamma");

		Assert.assertEquals(
			Arrays.asList("alpha", "beta"),
			_urlTitles(_getCollectionInfoPage()));
	}

	@Test
	public void testGuestReturnsEmptyWithoutConsultingUserGroups()
		throws Exception {

		_pushServiceContext(0);
		_configure("Riders=alpha");

		Assert.assertTrue(
			_getCollectionInfoPage().getPageItems().isEmpty());

		Mockito.verify(
			_userGroupLocalService, Mockito.never()
		).getUserUserGroups(
			Mockito.anyLong()
		);
	}

	@Test
	public void testMalformedConfigurationLineIsSkipped() throws Exception {
		_pushServiceContext(_USER_ID);
		_givenUserGroups("Riders");
		_givenEntries("alpha");

		_configure("this line has no equals sign", "Riders=alpha");

		Assert.assertEquals(
			Arrays.asList("alpha"), _urlTitles(_getCollectionInfoPage()));
	}

	@Test
	public void testMissingServiceContextReturnsEmptyRatherThanThrowing()
		throws Exception {

		ServiceContextThreadLocal.remove();

		_configure("Riders=alpha");

		InfoPage<BlogsEntry> infoPage = _getCollectionInfoPage();

		Assert.assertTrue(infoPage.getPageItems().isEmpty());
		Assert.assertEquals(0, infoPage.getTotalCount());
	}

	@Test
	public void testPaginationSlicesWithoutLosingTotalCount()
		throws Exception {

		_pushServiceContext(_USER_ID);
		_givenUserGroups("Riders");
		_givenEntries("alpha", "beta", "gamma");

		_configure("Riders=alpha,beta,gamma");

		CollectionQuery collectionQuery = new CollectionQuery();

		collectionQuery.setPagination(Pagination.of(2, 0));

		InfoPage<BlogsEntry> infoPage =
			_infoCollectionProvider.getCollectionInfoPage(collectionQuery);

		Assert.assertEquals(2, infoPage.getPageItems().size());
		Assert.assertEquals(3, infoPage.getTotalCount());
	}

	@Test
	public void testReferenceResolvesByExternalReferenceCodeThenUrlTitleThenId()
		throws Exception {

		_pushServiceContext(_USER_ID);
		_givenUserGroups("Riders");

		BlogsEntry byErc = _entry("by-erc");

		_byExternalReferenceCode.put("ERC-1", byErc);

		_givenEntries("by-url-title");

		BlogsEntry byId = _entry("by-id");

		_byEntryId.put(4242L, byId);

		_configure("Riders=ERC-1,by-url-title,4242");

		Assert.assertEquals(
			Arrays.asList("by-erc", "by-url-title", "by-id"),
			_urlTitles(_getCollectionInfoPage()));
	}

	@Test
	public void testUnionCombinesAndDeduplicatesInConfigurationOrder()
		throws Exception {

		_pushServiceContext(_USER_ID);
		_givenUserGroups("Riders", "Engineering");
		_givenEntries("alpha", "beta", "gamma");

		_configureWith(
			UserGroupRecommendationsInfoCollectionProvider.STRATEGY_UNION,
			UserGroupRecommendationsInfoCollectionProvider.FALLBACK_EMPTY,
			"Riders=alpha,beta", "Engineering=beta,gamma");

		Assert.assertEquals(
			Arrays.asList("alpha", "beta", "gamma"),
			_urlTitles(_getCollectionInfoPage()));
	}

	@Test
	public void testUnresolvableAndUnapprovedReferencesAreSkipped()
		throws Exception {

		_pushServiceContext(_USER_ID);
		_givenUserGroups("Riders");
		_givenEntries("alpha", "gamma");

		BlogsEntry draft = Mockito.mock(BlogsEntry.class);

		Mockito.when(draft.getUrlTitle()).thenReturn("draft");
		Mockito.when(
			draft.getStatus()
		).thenReturn(
			WorkflowConstants.STATUS_DRAFT
		);

		_byUrlTitle.put("draft", draft);

		// "ghost" resolves to nothing, standing in for an entry deleted after
		// the configuration was written.

		_configure("Riders=alpha,draft,ghost,gamma");

		Assert.assertEquals(
			Arrays.asList("alpha", "gamma"),
			_urlTitles(_getCollectionInfoPage()));
	}

	@Test
	public void testUserGroupNameMatchingIsCaseInsensitive() throws Exception {
		_pushServiceContext(_USER_ID);
		_givenUserGroups("Riders");
		_givenEntries("alpha");

		_configure("riders=alpha");

		Assert.assertEquals(
			Arrays.asList("alpha"), _urlTitles(_getCollectionInfoPage()));
	}

	private void _configure(String... userGroupEntries) throws Exception {
		_configureWith(
			UserGroupRecommendationsInfoCollectionProvider.
				STRATEGY_FIRST_MATCH,
			UserGroupRecommendationsInfoCollectionProvider.FALLBACK_EMPTY,
			userGroupEntries);
	}

	private void _configureWith(
			String multiGroupStrategy, String fallback,
			String... userGroupEntries)
		throws Exception {

		_setField(
			_infoCollectionProvider, "_configuration",
			new StubConfiguration(
				userGroupEntries, multiGroupStrategy, fallback));
	}

	private BlogsEntry _entry(String urlTitle) {
		BlogsEntry blogsEntry = Mockito.mock(BlogsEntry.class);

		Mockito.when(blogsEntry.getUrlTitle()).thenReturn(urlTitle);
		Mockito.when(
			blogsEntry.getStatus()
		).thenReturn(
			WorkflowConstants.STATUS_APPROVED
		);

		return blogsEntry;
	}

	private InfoPage<BlogsEntry> _getCollectionInfoPage() {
		CollectionQuery collectionQuery = new CollectionQuery();

		collectionQuery.setPagination(Pagination.of(20, 0));

		return _infoCollectionProvider.getCollectionInfoPage(collectionQuery);
	}

	private void _givenEntries(String... urlTitles) {
		for (String urlTitle : urlTitles) {
			_byUrlTitle.put(urlTitle, _entry(urlTitle));
		}
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

	private List<String> _urlTitles(InfoPage<BlogsEntry> infoPage) {
		List<String> urlTitles = new ArrayList<>();

		for (BlogsEntry blogsEntry : infoPage.getPageItems()) {
			urlTitles.add(blogsEntry.getUrlTitle());
		}

		return urlTitles;
	}

	private static final long _COMPANY_ID = 20099L;

	private static final long _SCOPE_GROUP_ID = 20123L;

	private static final long _USER_ID = 42L;

	private BlogsEntryLocalService _blogsEntryLocalService;
	private Map<String, BlogsEntry> _byExternalReferenceCode;
	private Map<Long, BlogsEntry> _byEntryId;
	private Map<String, BlogsEntry> _byUrlTitle;
	private UserGroupRecommendationsInfoCollectionProvider
		_infoCollectionProvider;
	private Language _language;
	private UserGroupLocalService _userGroupLocalService;

	/**
	 * A plain implementation rather than a Mockito mock, so the test reads as
	 * the configuration an administrator would actually write.
	 */
	private static class StubConfiguration
		implements UserGroupRecommendationsConfiguration {

		public StubConfiguration(
			String[] userGroupEntries, String multiGroupStrategy,
			String fallback) {

			_userGroupEntries = userGroupEntries;
			_multiGroupStrategy = multiGroupStrategy;
			_fallback = fallback;
		}

		@Override
		public String fallback() {
			return _fallback;
		}

		@Override
		public String multiGroupStrategy() {
			return _multiGroupStrategy;
		}

		@Override
		public String label() {
			return "Recommended for Your Group";
		}

		@Override
		public String[] objectDefinitionExternalReferenceCodes() {
			return new String[0];
		}

		@Override
		public String[] objectUserGroupEntries() {
			return new String[0];
		}

		@Override
		public String[] userGroupEntries() {
			return _userGroupEntries;
		}

		private final String _fallback;
		private final String _multiGroupStrategy;
		private final String[] _userGroupEntries;

	}

}
