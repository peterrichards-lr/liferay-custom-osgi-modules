package com.liferay.commerce.site.type;

import java.util.Collections;
import java.util.Set;

import jakarta.ws.rs.core.Application;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;

@Component(
	property = {
		JaxrsWhiteboardConstants.JAX_RS_APPLICATION_BASE + "=/commerce-site-type",
		JaxrsWhiteboardConstants.JAX_RS_NAME + "=Custom.Commerce.Site.Type",
		"auth.verifier.guest.allowed=false",
		"liferay.access.control.disable=false"
	},
	service = Application.class
)
public class CommerceSiteTypeApplication extends Application {

	@Override
	public Set<Object> getSingletons() {
		return Collections.singleton(_commerceSiteTypeResource);
	}

	private final CommerceSiteTypeResource _commerceSiteTypeResource = new CommerceSiteTypeResource();

}
