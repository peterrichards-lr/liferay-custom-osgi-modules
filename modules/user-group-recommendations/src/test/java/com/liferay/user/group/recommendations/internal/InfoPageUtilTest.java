package com.liferay.user.group.recommendations.internal;

import com.liferay.info.pagination.InfoPage;
import com.liferay.info.pagination.Pagination;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * Regression cover for the pagination the page editor actually sends.
 *
 * <p>
 * The original providers clamped only the upper bound, so the count request the
 * editor makes through {@code LayoutWarningMessageHelperImpl#_getTotalCount} --
 * which carries {@code QueryUtil.ALL_POS} (-1) to mean "all" -- reached
 * {@code subList(-1, …)} and threw {@code IndexOutOfBoundsException: fromIndex =
 * -1}. The collection then failed to render at all. Every case here existed in
 * the old tests except the negative ones, which is exactly why it shipped.
 * </p>
 *
 * @author Peter Richards
 */
public class InfoPageUtilTest {

	@Test
	public void testAllPosEndReturnsEverything() {
		Assert.assertEquals(
			_ITEMS, _paginate(_ITEMS, Pagination.of(-1, 0)).getPageItems());
	}

	@Test
	public void testAllPosStartAndEndReturnsEverything() {

		// The exact call the page editor makes when asking only for a count.

		InfoPage<String> infoPage = _paginate(_ITEMS, Pagination.of(-1, -1));

		Assert.assertEquals(_ITEMS, infoPage.getPageItems());
		Assert.assertEquals(3, infoPage.getTotalCount());
	}

	@Test
	public void testAllPosStartReturnsEverything() {
		Assert.assertEquals(
			_ITEMS, _paginate(_ITEMS, Pagination.of(20, -1)).getPageItems());
	}

	@Test
	public void testEmptyListWithAllPosDoesNotThrow() {
		InfoPage<String> infoPage = _paginate(
			Collections.emptyList(), Pagination.of(-1, -1));

		Assert.assertTrue(infoPage.getPageItems().isEmpty());
		Assert.assertEquals(0, infoPage.getTotalCount());
	}

	@Test
	public void testEndBeyondSizeIsClamped() {
		InfoPage<String> infoPage = _paginate(_ITEMS, Pagination.of(99, 0));

		Assert.assertEquals(_ITEMS, infoPage.getPageItems());
		Assert.assertEquals(3, infoPage.getTotalCount());
	}

	@Test
	public void testNullPaginationReturnsEverything() {
		InfoPage<String> infoPage = _paginate(_ITEMS, null);

		Assert.assertEquals(_ITEMS, infoPage.getPageItems());
		Assert.assertEquals(3, infoPage.getTotalCount());
	}

	@Test
	public void testSlicesWithoutLosingTotalCount() {
		InfoPage<String> infoPage = _paginate(_ITEMS, Pagination.of(2, 0));

		Assert.assertEquals(Arrays.asList("a", "b"), infoPage.getPageItems());
		Assert.assertEquals(3, infoPage.getTotalCount());
	}

	@Test
	public void testStartBeyondSizeYieldsEmptyPageNotAnError() {
		InfoPage<String> infoPage = _paginate(_ITEMS, Pagination.of(99, 99));

		Assert.assertTrue(infoPage.getPageItems().isEmpty());
		Assert.assertEquals(3, infoPage.getTotalCount());
	}

	private <T> InfoPage<T> _paginate(List<T> items, Pagination pagination) {
		return InfoPageUtil.paginate(items, pagination);
	}

	private static final List<String> _ITEMS = Arrays.asList("a", "b", "c");

}
