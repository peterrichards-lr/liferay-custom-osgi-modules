package com.liferay.user.group.recommendations.configuration;

import com.liferay.portal.configuration.metatype.annotations.ExtendedObjectClassDefinition;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Maps a user group to the entries its members should be recommended.
 *
 * <p>
 * This lives in OSGi configuration rather than in the page editor. An earlier
 * revision implemented {@code ConfigurableInfoCollectionProvider} and built the
 * mapping as a form in the Collection Display fragment, which was rejected for
 * three reasons found in use: the configuration is stored in the page, so it is
 * lost whenever the site is rebuilt; it has to be repeated for every placement
 * of the fragment; and it cannot be version-controlled, reviewed, or seeded by
 * a deployment script. Configuration deployed as a
 * {@code .config} file under {@code configs/<env>/osgi/configs/} has none of
 * those problems.
 * </p>
 *
 * @author Peter Richards
 */
@ExtendedObjectClassDefinition(category = "content-and-data")
@ObjectClassDefinition(
	description = "user-group-recommendations-configuration-description",
	id = "com.liferay.user.group.recommendations.configuration.UserGroupRecommendationsConfiguration",
	localization = "content/Language",
	name = "user-group-recommendations-configuration-name"
)
public interface UserGroupRecommendationsConfiguration {

	/**
	 * One entry per user group, as {@code <user group name>=<ref>,<ref>,...}.
	 *
	 * <p>
	 * Entries are served in the order written; the provider never re-sorts,
	 * because the point of naming them individually is that the sequence is
	 * chosen.
	 * </p>
	 *
	 * <p>
	 * Each {@code ref} is resolved as an external reference code, then as a
	 * friendly URL (the {@code urlTitle}), then as a numeric entry id. Prefer
	 * one of the first two: entry ids differ between environments, so a
	 * {@code .config} written against one instance will silently resolve to
	 * nothing -- or worse, to the wrong entries -- on another.
	 * </p>
	 *
	 * <p>
	 * User groups are matched by name, case-insensitively, for the same
	 * portability reason. Renaming a user group therefore breaks the mapping;
	 * that is the accepted trade for a file that deploys unchanged everywhere.
	 * </p>
	 */
	@AttributeDefinition(
		description = "user-group-entries-description",
		name = "user-group-entries", required = false
	)
	public String[] userGroupEntries();

	/**
	 * What to serve when a user belongs to more than one configured group.
	 * {@code firstMatch} uses the first group listed above that the user
	 * belongs to; {@code union} combines every match, de-duplicating while
	 * keeping the configured order.
	 */
	@AttributeDefinition(
		description = "multi-group-strategy-description",
		name = "multi-group-strategy",
		options = {
			@org.osgi.service.metatype.annotations.Option(
				label = "first-match", value = "firstMatch"
			),
			@org.osgi.service.metatype.annotations.Option(
				label = "union", value = "union"
			)
		},
		required = false
	)
	public String multiGroupStrategy();

	/**
	 * What to serve when a user belongs to no configured group, or is a guest.
	 * {@code empty} shows nothing; {@code recent} falls back to the most recent
	 * approved entries in the scope.
	 */
	@AttributeDefinition(
		description = "fallback-description", name = "fallback",
		options = {
			@org.osgi.service.metatype.annotations.Option(
				label = "empty", value = "empty"
			),
			@org.osgi.service.metatype.annotations.Option(
				label = "recent", value = "recent"
			)
		},
		required = false
	)
	public String fallback();

	/**
	 * External reference codes of the object definitions -- new CMS content
	 * types -- to serve, for example {@code L_CMS_BLOG} or the code of a custom
	 * type such as MotorBlog.
	 *
	 * <p>
	 * A collection provider is registered for each, per company. Leave empty to
	 * serve legacy Blogs only.
	 * </p>
	 */
	@AttributeDefinition(
		description = "object-definition-external-reference-codes-description",
		name = "object-definition-external-reference-codes", required = false
	)
	public String[] objectDefinitionExternalReferenceCodes();

	/**
	 * One line per object content type and user group, as
	 * {@code <object definition code>|<user group name>=<entry code>,...}.
	 *
	 * <p>
	 * Entry references are object entry external reference codes, which are
	 * portable between environments; a numeric entry id is accepted as a
	 * fallback but should not be committed to a shared configuration file.
	 * </p>
	 */
	@AttributeDefinition(
		description = "object-user-group-entries-description",
		name = "object-user-group-entries", required = false
	)
	public String[] objectUserGroupEntries();

	/**
	 * Label shown in the page editor's collection picker.
	 */
	@AttributeDefinition(
		description = "label-description", name = "label", required = false
	)
	public String label();

}
