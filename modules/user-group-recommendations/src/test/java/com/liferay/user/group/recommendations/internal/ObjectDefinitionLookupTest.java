package com.liferay.user.group.recommendations.internal;

import com.liferay.object.model.ObjectDefinition;
import com.liferay.object.service.ObjectDefinitionLocalService;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

import java.lang.reflect.Method;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Covers resolving a configured value to an object definition.
 *
 * <p>
 * The case that broke the first deployment is
 * {@link #testResolvesByLabelWhenNameIsRecapitalised()}. Liferay derives a
 * definition's name from its label with its own capitalisation -- label
 * {@code MotorBlog} becomes name {@code Motorblog} -- and the label is the only
 * one of the two an administrator sees. A fallback existed but scanned an empty
 * list, because the persistence finder behind it matches {@code status}
 * exactly and was being passed {@code QueryUtil.ALL_POS} (-1) rather than
 * {@code STATUS_APPROVED} (0). {@link #testScanRequestsApprovedNotAllPos()}
 * pins that.
 * </p>
 *
 * @author Peter Richards
 */
public class ObjectDefinitionLookupTest {

	@Before
	public void setUp() throws Exception {
		_objectDefinitionLocalService = Mockito.mock(
			ObjectDefinitionLocalService.class);

		_registrar = new UserGroupRecommendationsObjectRegistrar();

		_setField(
			_registrar, "_objectDefinitionLocalService",
			_objectDefinitionLocalService);

		_fetchObjectDefinition =
			UserGroupRecommendationsObjectRegistrar.class.getDeclaredMethod(
				"_fetchObjectDefinition", long.class, String.class);

		_fetchObjectDefinition.setAccessible(true);
	}

	@Test
	public void testPrefersExternalReferenceCode() throws Exception {
		ObjectDefinition byErc = _definition("Whatever", "Whatever", "Whatever");

		Mockito.when(
			_objectDefinitionLocalService.
				fetchObjectDefinitionByExternalReferenceCode(
					"ERC-1", _COMPANY_ID)
		).thenReturn(
			byErc
		);

		Assert.assertSame(byErc, _fetch("ERC-1"));
	}

	@Test
	public void testResolvesByCustomPrefixedName() throws Exception {
		ObjectDefinition definition = _definition(
			"C_Widget", "Widget", "Widget");

		Mockito.when(
			_objectDefinitionLocalService.fetchObjectDefinition(
				_COMPANY_ID, "C_Widget")
		).thenReturn(
			definition
		);

		Assert.assertSame(definition, _fetch("Widget"));
	}

	@Test
	public void testResolvesByExactName() throws Exception {
		ObjectDefinition definition = _definition(
			"Motorblog", "Motorblog", "MotorBlog");

		Mockito.when(
			_objectDefinitionLocalService.fetchObjectDefinition(
				_COMPANY_ID, "Motorblog")
		).thenReturn(
			definition
		);

		Assert.assertSame(definition, _fetch("Motorblog"));
	}

	@Test
	public void testResolvesByLabelWhenNameIsRecapitalised() throws Exception {

		// The exact prod shape: label "MotorBlog", name "Motorblog". Writing
		// the label into configuration must work, because it is what the
		// administrator can see.

		ObjectDefinition definition = _definition(
			"Motorblog", "Motorblog", "MotorBlog");

		_givenApprovedDefinitions(definition);

		Assert.assertSame(definition, _fetch("MotorBlog"));
	}

	@Test
	public void testResolvesByShortName() throws Exception {
		ObjectDefinition definition = _definition("C_Thing", "Thing", "A Thing");

		_givenApprovedDefinitions(definition);

		Assert.assertSame(definition, _fetch("thing"));
	}

	@Test
	public void testReturnsNullWhenNothingMatches() throws Exception {
		_givenApprovedDefinitions(
			_definition("Motorblog", "Motorblog", "MotorBlog"));

		Assert.assertNull(_fetch("SomethingElse"));
	}

	@Test
	public void testScanRequestsApprovedNotAllPos() throws Exception {
		_givenApprovedDefinitions(
			_definition("Motorblog", "Motorblog", "MotorBlog"));

		_fetch("MotorBlog");

		// The regression. findByC_A_S matches status exactly, so -1 returns
		// nothing and the scan never sees a single definition.

		Mockito.verify(
			_objectDefinitionLocalService
		).getObjectDefinitions(
			_COMPANY_ID, true, WorkflowConstants.STATUS_APPROVED
		);

		Mockito.verify(
			_objectDefinitionLocalService, Mockito.never()
		).getObjectDefinitions(
			Mockito.anyLong(), Mockito.anyBoolean(),
			Mockito.eq(WorkflowConstants.STATUS_ANY)
		);
	}

	private ObjectDefinition _definition(
		String name, String shortName, String label) {

		ObjectDefinition objectDefinition = Mockito.mock(
			ObjectDefinition.class);

		Mockito.when(objectDefinition.getName()).thenReturn(name);
		Mockito.when(objectDefinition.getShortName()).thenReturn(shortName);

		Map<Locale, String> labelMap = new HashMap<>();

		labelMap.put(Locale.US, label);

		Mockito.when(objectDefinition.getLabelMap()).thenReturn(labelMap);

		return objectDefinition;
	}

	private ObjectDefinition _fetch(String reference) throws Exception {
		return (ObjectDefinition)_fetchObjectDefinition.invoke(
			_registrar, _COMPANY_ID, reference);
	}

	private void _givenApprovedDefinitions(ObjectDefinition... definitions) {
		List<ObjectDefinition> list = Arrays.asList(definitions);

		Mockito.when(
			_objectDefinitionLocalService.getObjectDefinitions(
				_COMPANY_ID, true, WorkflowConstants.STATUS_APPROVED)
		).thenReturn(
			list
		);

		Mockito.when(
			_objectDefinitionLocalService.getObjectDefinitions(
				_COMPANY_ID, true, WorkflowConstants.STATUS_ANY)
		).thenReturn(
			Collections.emptyList()
		);
	}

	private void _setField(Object target, String name, Object value)
		throws Exception {

		java.lang.reflect.Field field = target.getClass().getDeclaredField(name);

		field.setAccessible(true);

		field.set(target, value);
	}

	private static final long _COMPANY_ID = 57375373476621L;

	private Method _fetchObjectDefinition;
	private ObjectDefinitionLocalService _objectDefinitionLocalService;
	private UserGroupRecommendationsObjectRegistrar _registrar;

}
