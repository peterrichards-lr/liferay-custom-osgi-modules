package com.liferay.dxp.routes.domain.internal;

import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Removes a port the routes writer appended to the published DXP domain.
 *
 * <p>
 * Kept free of portal state so that the decision and the file handling can be
 * tested without a running portal. See {@link
 * DXPRoutesDomainPortalInetSocketAddressEventListener} for why it exists.
 * </p>
 *
 * @author Peter Richards
 */
public class DXPRoutesDomainUtil {

	/**
	 * The files carrying the published authority. {@code domains} holds one
	 * entry per line.
	 */
	public static final String[] DOMAIN_FILE_NAMES = {
		"com.liferay.lxc.dxp.domains", "com.liferay.lxc.dxp.main.domain",
		"com.liferay.lxc.dxp.mainDomain"
	};

	/**
	 * Whether an address event is the one that publishes the wrong port.
	 *
	 * <p>
	 * Only a non-secure event on a portal whose protocol is {@code https}. The
	 * routes writer's own initial write asks for the secure port, so the
	 * non-secure port it appends on this event disagrees with it by
	 * construction. A secure event appends the secure port, which is the one
	 * that initial write would use, and is left alone.
	 * </p>
	 */
	public static boolean isMismatched(String webServerProtocol, boolean secure) {
		if (secure || !Objects.equals(webServerProtocol, "https")) {
			return false;
		}

		return true;
	}

	/**
	 * Strips {@code ":" + port} from the end of every domain entry under each
	 * {@code <routes>/<virtual-instance>/dxp} directory.
	 *
	 * @return the files changed
	 */
	public static List<Path> stripPort(Path routesPath, int port)
		throws IOException {

		List<Path> changedPaths = new ArrayList<>();

		if ((port <= 0) || !Files.isDirectory(routesPath)) {
			return changedPaths;
		}

		String suffix = ":" + port;

		try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(
				routesPath)) {

			for (Path virtualInstancePath : directoryStream) {
				Path dxpPath = virtualInstancePath.resolve("dxp");

				if (!Files.isDirectory(dxpPath)) {
					continue;
				}

				for (String fileName : DOMAIN_FILE_NAMES) {
					Path path = dxpPath.resolve(fileName);

					if (Files.isRegularFile(path) && _stripPort(path, suffix)) {
						changedPaths.add(path);
					}
				}
			}
		}

		return changedPaths;
	}

	private static boolean _stripPort(Path path, String suffix)
		throws IOException {

		String value = new String(
			Files.readAllBytes(path), StandardCharsets.UTF_8);

		String[] lines = value.split("\n", -1);

		boolean changed = false;

		for (int i = 0; i < lines.length; i++) {
			if (lines[i].endsWith(suffix)) {
				lines[i] = lines[i].substring(
					0, lines[i].length() - suffix.length());

				changed = true;
			}
		}

		if (changed) {

			// Rewrite in place rather than replace, so the file keeps its
			// inode and owner -- client extension containers mount this tree.

			Files.write(
				path, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
		}

		return changed;
	}

}
