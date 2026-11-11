package com.liferay.user.group.recommendations.internal;

import com.liferay.portal.kernel.log.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Sizes a recommendation set to the configured {@code itemCount}, topping a
 * short curated set up from the fallback pool when {@code topUp} is on (issue
 * #47).
 *
 * <p>
 * Every audience on the demo this was raised from is meant to show three
 * entries. One mistyped reference, or an entry unpublished or deleted, made the
 * block quietly render two -- in a three-across grid that reads as broken
 * rather than as a configuration error. A target count makes the block a fixed
 * size regardless of content churn, which is what a recommendations slot
 * usually wants.
 * </p>
 *
 * <p>
 * <b>Topping up is opt-in.</b> Mixing fallback entries into a hand-picked set
 * silently could mislead someone checking the result, who would not know one
 * of the three was filler. Without {@code topUp} a short set stays short, and
 * says so in the log. So does one that cannot be filled because the fallback
 * is {@code empty} or exhausted: the target is a preference, not a guarantee.
 * </p>
 *
 * <p>
 * Shared by the Blogs and object providers, which differ only in how an entry
 * is resolved and identified.
 * </p>
 *
 * @author Peter Richards
 */
public class ItemCountUtil {

	/**
	 * @param  curated the resolved hand-picked entries, in configured order
	 * @param  itemCount the target; zero or less serves today's behaviour --
	 *         the curated set as configured, or the fallback when it is empty
	 * @param  fallback the fallback's entries for a limit, where zero means
	 *         its own {@code fallbackLimit}; empty when the fallback is
	 *         {@code empty}
	 * @param  key the entry's primary key, to keep a top-up from repeating a
	 *         curated entry
	 * @param  description what the set is, for the log line
	 */
	public static <T> List<T> select(
		List<T> curated, int itemCount, boolean topUp,
		IntFunction<List<T>> fallback, Function<T, Object> key, Log log,
		String description) {

		if (itemCount <= 0) {
			if (curated.isEmpty()) {
				return fallback.apply(0);
			}

			return curated;
		}

		if (curated.isEmpty()) {
			return _trim(fallback.apply(itemCount), itemCount);
		}

		if (curated.size() >= itemCount) {
			return _trim(curated, itemCount);
		}

		if (!topUp) {
			_logShort(
				log, description, curated.size(), itemCount,
				"topUp is off");

			return curated;
		}

		List<T> selected = new ArrayList<>(curated);

		Set<Object> keys = new HashSet<>();

		for (T entry : curated) {
			keys.add(key.apply(entry));
		}

		// Ask for enough that duplicates of curated entries cannot leave the
		// set short when the pool could have filled it.

		for (T entry : fallback.apply(itemCount + curated.size())) {
			if (selected.size() >= itemCount) {
				break;
			}

			if (keys.add(key.apply(entry))) {
				selected.add(entry);
			}
		}

		if (selected.size() < itemCount) {
			_logShort(
				log, description, selected.size(), itemCount,
				"the fallback has no more entries to add");
		}

		return selected;
	}

	private static void _logShort(
		Log log, String description, int served, int itemCount,
		String reason) {

		if (log.isInfoEnabled()) {
			log.info(
				"Serving " + served + " of the target " + itemCount + " " +
					description + " entries: " + reason);
		}
	}

	private static <T> List<T> _trim(List<T> entries, int itemCount) {
		if (entries.size() > itemCount) {
			return new ArrayList<>(entries.subList(0, itemCount));
		}

		return entries;
	}

}
