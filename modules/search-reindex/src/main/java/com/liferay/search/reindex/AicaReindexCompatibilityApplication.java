package com.liferay.search.reindex;

import java.util.Collections;
import java.util.Set;

import jakarta.ws.rs.core.Application;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;

@Component(
	property = {
		JaxrsWhiteboardConstants.JAX_RS_APPLICATION_BASE + "=/aica-reindex",
		JaxrsWhiteboardConstants.JAX_RS_NAME + "=AICA.Reindex.Compatibility",
		"auth.verifier.guest.allowed=false",
		"liferay.access.control.disable=false"
	},
	service = Application.class
)
public class AicaReindexCompatibilityApplication extends Application {

	@Override
	public Set<Object> getSingletons() {
		return Collections.singleton(_searchReindexResource);
	}

	private final SearchReindexResource _searchReindexResource = new SearchReindexResource();

}
