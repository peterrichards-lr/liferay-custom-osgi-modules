package com.liferay.client.extension.entry;

import com.liferay.client.extension.service.ClientExtensionEntryLocalService;
import com.liferay.client.extension.type.manager.CETManager;

import java.util.Collections;
import java.util.Set;

import jakarta.ws.rs.core.Application;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;

/**
 * Publishes the read-only client extension entry endpoint.
 *
 * <p>
 * Unlike the other modules in this workspace, the resource cannot be a plain
 * <code>new</code>: it needs {@link CETManager}, which is an OSGi service and
 * has no <code>*Util</code> static accessor. The reference is therefore held
 * here and handed to the resource on activation, which also leaves the resource
 * constructor-injectable and so unit-testable without an OSGi container.
 * </p>
 *
 * <p>
 * The OAuth 2 scope a caller must be granted derives from
 * <code>osgi.jaxrs.name</code>: <code>Custom.Client.Extension.Entry</code>
 * yields <code>Custom.Client.Extension.Entry.everything.read</code>. Deploying
 * the bundle is not by itself sufficient — a service account without that grant
 * receives HTTP 403 with an empty body, which reads like a broken module rather
 * than a missing grant.
 * </p>
 */
@Component(
	property = {
		JaxrsWhiteboardConstants.JAX_RS_APPLICATION_BASE + "=/client-extension-entry",
		JaxrsWhiteboardConstants.JAX_RS_NAME + "=Custom.Client.Extension.Entry",
		"auth.verifier.guest.allowed=false",
		"liferay.access.control.disable=false"
	},
	service = Application.class
)
public class ClientExtensionEntryApplication extends Application {

	@Override
	public Set<Object> getSingletons() {
		return Collections.singleton(_clientExtensionEntryResource);
	}

	@Activate
	protected void activate() {
		_clientExtensionEntryResource = new ClientExtensionEntryResource(
			_cetManager, _clientExtensionEntryLocalService);
	}

	@Reference
	private CETManager _cetManager;

	@Reference
	private ClientExtensionEntryLocalService _clientExtensionEntryLocalService;

	private ClientExtensionEntryResource _clientExtensionEntryResource;

}
