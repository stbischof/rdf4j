/*******************************************************************************
 * Copyright (c) 2021 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/

package org.eclipse.rdf4j.spring.domain.model;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.sparqlbuilder.core.ExtendedVariable;

/**
 * @since 4.0.0
 * @author Florian Kleedorfer
 */
public class Artist {
	public static final ExtendedVariable ARTIST_ID = new ExtendedVariable("artist_id");
	public static final ExtendedVariable ARTIST_FIRST_NAME = new ExtendedVariable("artist_firstName");
	public static final ExtendedVariable ARTIST_LAST_NAME = new ExtendedVariable("artist_lastName");
	private IRI id;
	private String firstName;
	private String lastName;

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public IRI getId() {
		return id;
	}

	public void setId(IRI id) {
		this.id = id;
	}
}
