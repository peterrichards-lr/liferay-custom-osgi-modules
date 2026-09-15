package com.liferay.user.group.recommendations.internal;

import com.liferay.depot.constants.DepotConstants;
import com.liferay.depot.model.DepotEntry;
import com.liferay.depot.service.DepotEntryLocalServiceUtil;
import com.liferay.object.constants.ObjectDefinitionConstants;
import com.liferay.object.model.ObjectDefinition;
import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.theme.ThemeDisplay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the groups a collection should search.
 *
 * <p>
 * <b>Why this is not just {@code ServiceContext#getScopeGroupId}.</b> The same
 * {@code ServiceContext} that returns zero for the user id cannot be trusted for
 * the scope group either. Querying group zero finds nothing, silently, which is
 * indistinguishable from a site that genuinely has no content. The theme display
 * is the render-time source and is tried first.
 * </p>
 *
 * <p>
 * <b>Depot expansion.</b> A definition scoped to {@code depot} stores its entries
 * in an Asset Library rather than in the site the page belongs to, so the site
 * group alone finds nothing. Connected libraries are added via
 * {@code DepotEntryLocalServiceUtil#getGroupConnectedDepotEntries}, which is what
 * Liferay's own object collection provider uses.
 * </p>
 *
 * @author Peter Richards
 */
public class CurrentScopeUtil {

	/**
	 * The scope group for the current render, or {@code 0} if none can be
	 * determined.
	 */
	public static long getScopeGroupId(ServiceContext serviceContext) {
		if (serviceContext == null) {
			return 0;
		}

		ThemeDisplay themeDisplay = serviceContext.getThemeDisplay();

		if ((themeDisplay != null) && (themeDisplay.getScopeGroupId() > 0)) {
			return themeDisplay.getScopeGroupId();
		}

		return serviceContext.getScopeGroupId();
	}

	/**
	 * The scope group plus, for a depot-scoped definition, every Asset Library
	 * connected to it.
	 */
	public static long[] getSearchableGroupIds(
		ObjectDefinition objectDefinition, ServiceContext serviceContext) {

		long scopeGroupId = getScopeGroupId(serviceContext);

		if (scopeGroupId <= 0) {
			_log.error(
				"No scope group could be resolved for this render, so no " +
					"content can be found. This is a context problem, not a " +
						"configuration one.");

			return new long[0];
		}

		Set<Long> groupIds = new LinkedHashSet<>();

		groupIds.add(scopeGroupId);

		String scope = objectDefinition.getScope();

		if (ObjectDefinitionConstants.SCOPE_DEPOT.equals(scope)) {
			try {
				List<DepotEntry> depotEntries =
					DepotEntryLocalServiceUtil.getGroupConnectedDepotEntries(
						scopeGroupId, DepotConstants.TYPE_ANY,
						QueryUtil.ALL_POS, QueryUtil.ALL_POS);

				for (DepotEntry depotEntry : depotEntries) {
					groupIds.add(depotEntry.getGroupId());
				}

				if (depotEntries.isEmpty() && _log.isInfoEnabled()) {
					_log.info(
						"Definition " +
							objectDefinition.getExternalReferenceCode() +
								" is depot scoped but no asset library is " +
									"connected to group " + scopeGroupId +
										", so its entries are unreachable " +
											"from this site");
				}
			}
			catch (Exception exception) {
				_log.error(
					"Unable to resolve asset libraries connected to group " +
						scopeGroupId,
					exception);
			}
		}

		List<Long> ordered = new ArrayList<>(groupIds);

		long[] result = new long[ordered.size()];

		for (int i = 0; i < ordered.size(); i++) {
			result[i] = ordered.get(i);
		}

		if (_log.isInfoEnabled()) {
			_log.info(
				"Searching groups " + ordered + " for " +
					objectDefinition.getExternalReferenceCode() + " (scope " +
						scope + ")");
		}

		return result;
	}

	private static final Log _log = LogFactoryUtil.getLog(
		CurrentScopeUtil.class);

}
