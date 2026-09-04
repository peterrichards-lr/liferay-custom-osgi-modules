package com.liferay.fragment.override.application;

import java.util.Collections;
import java.util.Set;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;

/**
 * Scaffold for the fragment-override endpoint. Deliberately not implemented.
 *
 * <p>
 * The restriction is Liferay's, not any one project's: the Headless API
 * rejects specification updates on published site initializer pages, so
 * every tool that needs to rewrite fragment configuration hits it. LDM was
 * simply the first here.
 * </p>
 *
 * <p>
 * LDM's current workaround (liferay-docker-manager#1601) rewrites
 * {@code fragmententrylink.editablevalues} with a direct SQL
 * {@code REGEXP_REPLACE} because the Headless API rejects the update on
 * published site initializer pages (liferay-docker-manager#883). That
 * workaround is a regex over a JSON column, its {@code WHERE} clause is
 * unscoped, it only supports PostgreSQL/MySQL, and -- decisively -- the portal
 * caches fragment configuration in memory and no Gogo command can invalidate
 * it, so the patched rows stay invisible until a restart.
 * </p>
 *
 * <p>
 * Running inside the portal JVM removes all of that, because
 * {@code FragmentEntryLinkLocalService.updateFragmentEntryLink(userId,
 * fragmentEntryLinkId, editableValues, updateClassedModel)} goes through the
 * service layer, which owns cache invalidation, model listeners and indexing.
 * </p>
 *
 * <p>
 * <strong>Do not implement this yet.</strong> Two configuration routes must be
 * ruled out first, either of which makes this module unnecessary:
 * </p>
 *
 * <ol>
 * <li>A feature flag may already unlock the PUT. The sibling restriction on
 * {@code POST /v1.0/sites/{siteId}/site-pages} is gated by
 * {@code feature.flag.LPS-178052=true}, and LDM already writes
 * {@code portal-ext.properties}.</li>
 * <li>Site Initializer update support (LPS-165482) exposes a Synchronize
 * action that may be the supported path outright.</li>
 * </ol>
 *
 * @author peterrichards
 */
@Component(
	property = {
		JaxrsWhiteboardConstants.JAX_RS_APPLICATION_BASE + "=/fragment-override",
		JaxrsWhiteboardConstants.JAX_RS_NAME + "=FragmentOverride"
	},
	service = Application.class
)
public class FragmentOverrideApplication extends Application {

	public Set<Object> getSingletons() {
		return Collections.<Object>singleton(this);
	}

	/**
	 * Reports that the bundle resolved and started. This exists so a
	 * deployment can be verified end to end before any behaviour is written --
	 * it asserts nothing about fragment overrides, which are not implemented.
	 */
	@GET
	@Path("/status")
	@Produces("text/plain")
	public String status() {
		return "fragment-override: scaffold only, no behaviour implemented "
			+ "(see liferay-docker-manager#1601)";
	}

}
