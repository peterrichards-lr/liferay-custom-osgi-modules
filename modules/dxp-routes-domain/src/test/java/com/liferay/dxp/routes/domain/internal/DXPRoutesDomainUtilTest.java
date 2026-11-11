package com.liferay.dxp.routes.domain.internal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The values here are the ones measured on DXP 2026.q3.5 behind Traefik: the
 * agent writes {@code aica-e2e.demo}, then {@code aica-e2e.demo:8080}.
 *
 * @author Peter Richards
 */
public class DXPRoutesDomainUtilTest {

	@Test
	public void testAnotherPortIsKept() throws Exception {
		Path dxpPath = _dxp("default");

		_write(dxpPath, "com.liferay.lxc.dxp.main.domain", "aica-e2e.demo:8443");

		Assert.assertTrue(
			DXPRoutesDomainUtil.stripPort(
				temporaryFolder.getRoot().toPath(), 8080).isEmpty());
		Assert.assertEquals(
			"aica-e2e.demo:8443",
			_read(dxpPath, "com.liferay.lxc.dxp.main.domain"));
	}

	@Test
	public void testEveryDomainFileIsCorrected() throws Exception {
		Path dxpPath = _dxp("default");

		_write(dxpPath, "com.liferay.lxc.dxp.main.domain", "aica-e2e.demo:8080");
		_write(dxpPath, "com.liferay.lxc.dxp.mainDomain", "aica-e2e.demo:8080");
		_write(
			dxpPath, "com.liferay.lxc.dxp.domains",
			"aica-e2e.demo:8080\nother.demo:8080");
		_write(dxpPath, "com.liferay.lxc.dxp.server.protocol", "https");

		List<Path> paths = DXPRoutesDomainUtil.stripPort(
			temporaryFolder.getRoot().toPath(), 8080);

		Assert.assertEquals(paths.toString(), 3, paths.size());
		Assert.assertEquals(
			"aica-e2e.demo", _read(dxpPath, "com.liferay.lxc.dxp.main.domain"));
		Assert.assertEquals(
			"aica-e2e.demo", _read(dxpPath, "com.liferay.lxc.dxp.mainDomain"));
		Assert.assertEquals(
			"aica-e2e.demo\nother.demo",
			_read(dxpPath, "com.liferay.lxc.dxp.domains"));
		Assert.assertEquals(
			"https", _read(dxpPath, "com.liferay.lxc.dxp.server.protocol"));
	}

	@Test
	public void testEveryVirtualInstanceIsCorrected() throws Exception {
		_write(_dxp("default"), "com.liferay.lxc.dxp.main.domain", "a.demo:8080");
		_write(_dxp("other"), "com.liferay.lxc.dxp.main.domain", "b.demo:8080");

		Assert.assertEquals(
			2,
			DXPRoutesDomainUtil.stripPort(
				temporaryFolder.getRoot().toPath(), 8080).size());
	}

	@Test
	public void testNoPortIsNoChange() throws Exception {
		_write(_dxp("default"), "com.liferay.lxc.dxp.main.domain", "a.demo:8080");

		Assert.assertTrue(
			DXPRoutesDomainUtil.stripPort(
				temporaryFolder.getRoot().toPath(), -1).isEmpty());
	}

	@Test
	public void testOnlyTheNonSecureEventOnAnHttpsPortalIsMismatched() {
		Assert.assertTrue(DXPRoutesDomainUtil.isMismatched("https", false));
		Assert.assertFalse(DXPRoutesDomainUtil.isMismatched("https", true));
		Assert.assertFalse(DXPRoutesDomainUtil.isMismatched("http", false));
		Assert.assertFalse(DXPRoutesDomainUtil.isMismatched("", false));
		Assert.assertFalse(DXPRoutesDomainUtil.isMismatched(null, false));
	}

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Path _dxp(String virtualInstanceId) throws Exception {
		return Files.createDirectories(
			temporaryFolder.getRoot().toPath().resolve(
				virtualInstanceId + "/dxp"));
	}

	private String _read(Path dxpPath, String fileName) throws Exception {
		return new String(
			Files.readAllBytes(dxpPath.resolve(fileName)),
			StandardCharsets.UTF_8);
	}

	private void _write(Path dxpPath, String fileName, String value)
		throws Exception {

		Files.write(
			dxpPath.resolve(fileName), value.getBytes(StandardCharsets.UTF_8));
	}

}
