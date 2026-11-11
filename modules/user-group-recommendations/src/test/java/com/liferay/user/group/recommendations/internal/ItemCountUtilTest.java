package com.liferay.user.group.recommendations.internal;

import com.liferay.portal.kernel.log.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * The table in issue #47, row by row, plus what happens when the target cannot
 * be met.
 *
 * @author Peter Richards
 */
public class ItemCountUtilTest {

	@Before
	public void setUp() {
		_log = Mockito.mock(Log.class);

		Mockito.when(_log.isInfoEnabled()).thenReturn(true);
	}

	@Test
	public void testAShortSetStaysShortAndSaysSoWithoutTopUp() {
		Assert.assertEquals(
			Arrays.asList("a", "b"),
			_select(Arrays.asList("a", "b"), 3, false, _pool("x", "y")));

		Mockito.verify(
			_log
		).info(
			"Serving 2 of the target 3 Blogs entries: topUp is off"
		);
	}

	@Test
	public void testAShortSetTopsUpFromTheFallback() {

		// Row 2: 3 configured, 2 resolve, target 3.

		Assert.assertEquals(
			Arrays.asList("a", "b", "x"),
			_select(Arrays.asList("a", "b"), 3, true, _pool("x", "y")));
	}

	@Test
	public void testAnEmptyFallbackLeavesTheSetShortAndSaysSo() {
		Assert.assertEquals(
			Arrays.asList("a", "b"),
			_select(Arrays.asList("a", "b"), 3, true, _pool()));

		Mockito.verify(
			_log
		).info(
			"Serving 2 of the target 3 Blogs entries: the fallback has no " +
				"more entries to add"
		);
	}

	@Test
	public void testAnExactSetIsServedInOrder() {

		// Row 1: 3 configured, 3 resolve, target 3.

		Assert.assertEquals(
			Arrays.asList("c", "a", "b"),
			_select(Arrays.asList("c", "a", "b"), 3, true, _pool("x")));
	}

	@Test
	public void testALongerSetIsTrimmedToTheFirstEntries() {

		// Row 3: 5 configured, 5 resolve, target 3.

		Assert.assertEquals(
			Arrays.asList("a", "b", "c"),
			_select(
				Arrays.asList("a", "b", "c", "d", "e"), 3, false, _pool()));
	}

	@Test
	public void testNoMatchServesTheFallbackSizedToTheTarget() {

		// Row 4: no group matched, target 3.

		Assert.assertEquals(
			Arrays.asList("x", "y", "z"),
			_select(
				Collections.<String>emptyList(), 3, false,
				_pool("x", "y", "z", "w")));
		Assert.assertEquals(Integer.valueOf(3), _requested.get(0));
	}

	@Test
	public void testTheTopUpNeverRepeatsACuratedEntry() {
		Assert.assertEquals(
			Arrays.asList("a", "b", "y"),
			_select(Arrays.asList("a", "b"), 3, true, _pool("a", "b", "y")));
		Assert.assertEquals(Integer.valueOf(5), _requested.get(0));
	}

	@Test
	public void testZeroKeepsTodaysBehaviour() {
		Assert.assertEquals(
			Arrays.asList("a", "b", "c", "d"),
			_select(Arrays.asList("a", "b", "c", "d"), 0, true, _pool("x")));

		Assert.assertEquals(
			Arrays.asList("x", "y"),
			_select(Collections.<String>emptyList(), 0, true, _pool("x", "y")));
		Assert.assertEquals(Integer.valueOf(0), _requested.get(0));

		Mockito.verify(
			_log, Mockito.never()
		).info(
			Mockito.anyString()
		);
	}

	private IntFunction<List<String>> _pool(String... entries) {
		_requested.clear();

		return limit -> {
			_requested.add(limit);

			List<String> pool = new ArrayList<>(Arrays.asList(entries));

			if ((limit > 0) && (pool.size() > limit)) {
				return pool.subList(0, limit);
			}

			return pool;
		};
	}

	private List<String> _select(
		List<String> curated, int itemCount, boolean topUp,
		IntFunction<List<String>> fallback) {

		return ItemCountUtil.select(
			curated, itemCount, topUp, fallback, Function.identity()::apply,
			_log, "Blogs");
	}

	private Log _log;
	private final List<Integer> _requested = new ArrayList<>();

}
