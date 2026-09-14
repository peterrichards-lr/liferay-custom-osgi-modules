package com.liferay.user.group.recommendations.internal;

import com.liferay.info.pagination.InfoPage;
import com.liferay.info.pagination.Pagination;

import java.util.List;

/**
 * Turns a resolved list into an {@link InfoPage}, tolerating the pagination the
 * page editor actually sends.
 *
 * <p>
 * <b>Why this is not inline in each provider.</b> It was, and it was wrong in
 * both. The editor asks a provider for a count before it renders anything --
 * {@code LayoutWarningMessageHelperImpl#_getTotalCount} -- and does so with a
 * {@code Pagination} carrying {@code QueryUtil.ALL_POS} (-1) to mean "all".
 * Clamping only the upper bound left {@code subList(-1, …)} to throw
 * {@code IndexOutOfBoundsException: fromIndex = -1}, which surfaced as a
 * collection that failed to render with a stack trace in the log rather than as
 * an empty one. A negative bound is a request for everything, not an error.
 * </p>
 *
 * @author Peter Richards
 */
public class InfoPageUtil {

	public static <T> InfoPage<T> paginate(
		List<T> items, Pagination pagination) {

		int totalCount = items.size();

		if (pagination == null) {
			return InfoPage.of(items, Pagination.of(totalCount, 0), totalCount);
		}

		int start = pagination.getStart();
		int end = pagination.getEnd();

		// Either bound negative means "no limit". Return everything rather than
		// trying to slice, and keep the pagination the caller handed us so the
		// count it reads back is the one it asked about.

		if ((start < 0) || (end < 0)) {
			return InfoPage.of(items, pagination, totalCount);
		}

		start = Math.min(start, totalCount);
		end = Math.min(end, totalCount);

		if (start > end) {
			start = end;
		}

		return InfoPage.of(items.subList(start, end), pagination, totalCount);
	}

}
