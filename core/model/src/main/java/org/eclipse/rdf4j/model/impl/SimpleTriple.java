/*******************************************************************************
 * Copyright (c) 2020 Eclipse RDF4J contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.model.impl;

import java.util.Objects;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.base.AbstractTriple;

/**
 * A simple default implementation of the {@link Triple} interface.
 *
 * @author Pavel Mihaylov
 * @see SimpleValueFactory
 */
public class SimpleTriple extends AbstractTriple {

	/**
	 * The triple's subject.
	 */
	private Resource subject;

	/**
	 * The triple's predicate.
	 */
	private IRI predicate;

	/**
	 * The triple's object.
	 */
	private Value object;

	/**
	 * Creates a new Triple with the supplied subject, predicate and object.
	 * <p>
	 * Note that creating SimpleStatement objects directly via this constructor is not the recommended approach.
	 * Instead, use an instance of {@link org.eclipse.rdf4j.model.ValueFactory} to create new Triple objects.
	 *
	 * @param subject   The triple's subject, must not be <tt>null</tt>.
	 * @param predicate The triple's predicate, must not be <tt>null</tt>.
	 * @param object    The triple's object, must not be <tt>null</tt>.
	 *
	 * @see SimpleValueFactory#createTriple(Resource, IRI, Value)
	 */
	protected SimpleTriple(Resource subject, IRI predicate, Value object) {
		this.subject=Objects.requireNonNull(subject, "subject must not be null");
		this.predicate=Objects.requireNonNull(predicate, "predicate must not be null");
		this.object=Objects.requireNonNull(object, "object must not be null");
	}

	@Override
	public Resource getSubject() {
		return subject;
	}

	@Override
	public IRI getPredicate() {
		return predicate;
	}

	@Override
	public Value getObject() {
		return object;
	}

}
