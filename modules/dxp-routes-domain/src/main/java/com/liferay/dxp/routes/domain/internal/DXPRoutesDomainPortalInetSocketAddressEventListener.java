package com.liferay.dxp.routes.domain.internal;

import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.kernel.util.PortalInetSocketAddressEventListener;
import com.liferay.portal.kernel.util.PropsValues;

import java.net.InetSocketAddress;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.List;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Keeps Tomcat's plain-HTTP connector port out of the DXP domain published to
 * client extensions, on a portal served over HTTPS by a TLS-terminating proxy.
 *
 * <p>
 * <b>The defect.</b> {@code com.liferay.portal.k8s.agent.impl} writes the
 * {@code routes/<instance>/dxp} tree every client extension resolves the portal
 * from. It runs in every DXP, not only on Kubernetes. Read from the bytecode of
 * DXP 2026.q3.5, it chooses the port two different ways:
 * </p>
 *
 * <ul>
 * <li>
 * {@code RoutesPortalK8sConfigMapModifier._writeRoutes}, the initial write and
 * every company change, asks {@code Portal.getPortalLocalPort(secure)} with
 * {@code secure} taken from {@code web.server.protocol}. Behind a proxy that
 * terminates TLS, Tomcat never sees a secure request, so the answer is {@code
 * -1} and the bare host is written. That is correct.
 * </li>
 * <li>
 * Its address listener passes the <i>event's</i> {@code secure} flag instead.
 * The first plain request records the non-secure local address -- Tomcat's
 * connector, 8080 -- and fires the listener with {@code secure=false}, which
 * appends {@code :8080} to every domain. The proxy does not serve that port.
 * </li>
 * </ul>
 *
 * <p>
 * The published value then depends on which path wrote last. A client
 * extension reading it at startup builds its OAuth and API URLs against
 * {@code https://host:8080}.
 * </p>
 *
 * <p>
 * <b>The fix.</b> This listener registers at a lower ranking than the agent's,
 * which registers with none and so ranks 0. {@code PortalImpl} notifies through
 * a {@code ServiceTrackerList} opened without a comparator, which orders by
 * {@code Collections.reverseOrder()} -- highest ranking first -- so this runs
 * after the agent, synchronously, in the same request. It removes exactly the
 * port the agent just appended, and only on the mismatched event. The event
 * fires once per address, since {@code PortalImpl} records each with {@code
 * compareAndSet(null, ...)}, so nothing appends it again.
 * </p>
 *
 * <p>
 * <b>Ruled out first.</b> No property stops the append: {@code
 * _updatePortalLocalPort} appends whenever the port is positive, and only
 * skips an entry that already contains a colon, which a company virtual host
 * cannot. The {@code web.server.forwarded.*} headers change {@code
 * Portal.isSecure(request)}, not Tomcat's {@code request.isSecure()} that
 * {@code setPortalInetSocketAddresses} reads, nor {@code getLocalPort()}.
 * Making Tomcat mark every request secure publishes {@code :8080} from both
 * paths instead of one. Disabling the agent's component removes the tree
 * client extensions depend on. Correcting the files from outside the container
 * leaves a window, and misses a restart nobody is watching.
 * </p>
 *
 * <p>
 * Reported upstream as peterrichards-lr/liferay-docker-manager#2344. Remove
 * this module once the agent derives the flag from {@code web.server.protocol}
 * on both paths.
 * </p>
 *
 * @author Peter Richards
 */
@Component(
	property = "service.ranking:Integer=-1000",
	service = PortalInetSocketAddressEventListener.class
)
public class DXPRoutesDomainPortalInetSocketAddressEventListener
	implements PortalInetSocketAddressEventListener {

	@Override
	public void portalLocalInetSocketAddressConfigured(
		InetSocketAddress inetSocketAddress, boolean secure) {

		_stripPort(secure);
	}

	@Override
	public void portalServerInetSocketAddressConfigured(
		InetSocketAddress inetSocketAddress, boolean secure) {

		_stripPort(secure);
	}

	private void _stripPort(boolean secure) {
		if (!DXPRoutesDomainUtil.isMismatched(
				PropsValues.WEB_SERVER_PROTOCOL, secure)) {

			return;
		}

		int port = _portal.getPortalLocalPort(false);

		try {
			List<Path> paths = DXPRoutesDomainUtil.stripPort(
				Paths.get(PropsValues.LIFERAY_HOME, "routes"), port);

			if (!paths.isEmpty() && _log.isInfoEnabled()) {
				_log.info(
					"Removed connector port " + port +
						" from the published DXP domain in " + paths);
			}
		}
		catch (Exception exception) {
			_log.error("Unable to correct the published DXP domain", exception);
		}
	}

	private static final Log _log = LogFactoryUtil.getLog(
		DXPRoutesDomainPortalInetSocketAddressEventListener.class);

	@Reference
	private Portal _portal;

}
