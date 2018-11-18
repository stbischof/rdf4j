/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.rio.rdfxml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Stack;

import javax.xml.transform.sax.SAXResult;

import org.apache.commons.io.input.BOMInputStream;
import org.eclipse.rdf4j.common.net.ParsedURI;
import org.eclipse.rdf4j.common.xml.XMLReaderFactory;
import org.eclipse.rdf4j.common.xml.XMLUtil;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.rio.RioSetting;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFParser;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;
import org.eclipse.rdf4j.rio.helpers.XMLReaderBasedParser;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

/**
 * A parser for XML-serialized RDF. This parser operates directly on the SAX events generated by a SAX-enabled
 * XML parser. The XML parser should be compliant with SAX2. You should specify which SAX parser should be
 * used by setting the <code>org.xml.sax.driver</code> property. This parser is not thread-safe, therefore
 * it's public methods are synchronized.
 * <p>
 * To parse a document using this parser:
 * <ul>
 * <li>Create an instance of RDFXMLParser, optionally supplying it with your own ValueFactory.
 * <li>Set the RDFHandler.
 * <li>Optionally, set the ParseErrorListener and/or ParseLocationListener.
 * <li>Optionally, specify whether the parser should verify the data it parses and whether it should stop
 * immediately when it finds an error in the data (both default to <tt>true</tt>).
 * <li>Call the parse method.
 * </ul>
 * Example code:
 * 
 * <pre>
 * // Use the SAX2-compliant Xerces parser:
 * System.setProperty(&quot;org.xml.sax.driver&quot;, &quot;org.apache.xerces.parsers.SAXParser&quot;);
 * 
 * RDFParser parser = new RDFXMLParser();
 * parser.setRDFHandler(myRDFHandler);
 * parser.setParseErrorListener(myParseErrorListener);
 * parser.setVerifyData(true);
 * parser.stopAtFirstError(false);
 * 
 * // Parse the data from inputStream, resolving any
 * // relative URIs against http://foo/bar:
 * parser.parse(inputStream, &quot;http://foo/bar&quot;);
 * </pre>
 *
 * Note that JAXP entity expansion limits may apply.
 * Check the documentation on <a href="https://docs.oracle.com/javase/tutorial/jaxp/limits/limits.html">limits</a> 
 * and using the <a href="https://docs.oracle.com/javase/tutorial/jaxp/limits/using.html">jaxp.properties file</a>
 *  if you get one of the following errors:
 * <pre> 
 * JAXP00010001: The parser has encountered more than "64000" entity expansions in this document
 * JAXP00010004: The accumulated size of entities is ... that exceeded the "50,000,000" limit
 * </pre>
 * As a work-around, try passing <code>-DtotalEntitySizeLimit=0 -DentityExpansionLimit=0</code> to the JVM.
 *
 * @see org.eclipse.rdf4j.model.ValueFactory
 * @see org.eclipse.rdf4j.rio.RDFHandler
 * @see org.eclipse.rdf4j.rio.ParseErrorListener
 * @see org.eclipse.rdf4j.rio.ParseLocationListener
 * @author Arjohn Kampman
 */
public class RDFXMLParser extends XMLReaderBasedParser implements ErrorHandler {

	/*-----------*
	 * Variables *
	 *-----------*/

	/**
	 * A filter filtering calls to SAX methods specifically for this parser.
	 */
	private SAXFilter saxFilter;

	/**
	 * The base URI of the document. This variable is set when <tt>parse(inputStream, baseURI)</tt> is called
	 * and will not be changed during parsing.
	 */
	private String documentURI;

	/**
	 * The language of literal values as can be specified using xml:lang attributes. This variable is
	 * set/modified by the SAXFilter during parsing such that it always represents the language of the context
	 * in which elements are reported.
	 */
	private String xmlLang;

	/**
	 * A stack of node- and property elements.
	 */
	private Stack<Object> elementStack = new Stack<Object>();

	/**
	 * A set containing URIs that have been generated as a result of rdf:ID attributes. These URIs should be
	 * unique within a single document.
	 */
	private Set<IRI> usedIDs = new HashSet<IRI>();

	/*--------------*
	 * Constructors *
	 *--------------*/

	/**
	 * Creates a new RDFXMLParser that will use a {@link SimpleValueFactory} to create RDF model objects.
	 */
	public RDFXMLParser() {
		this(SimpleValueFactory.getInstance());
	}

	/**
	 * Creates a new RDFXMLParser that will use the supplied <tt>ValueFactory</tt> to create RDF model objects.
	 * 
	 * @param valueFactory
	 *        A ValueFactory.
	 */
	public RDFXMLParser(ValueFactory valueFactory) {
		super(valueFactory);

		// SAXFilter does some filtering and verifying of SAX events
		saxFilter = new SAXFilter(this);
	}

	/*---------*
	 * Methods *
	 *---------*/

	@Override
	public final RDFFormat getRDFFormat() {
		return RDFFormat.RDFXML;
	}

	/**
	 * Sets the parser in a mode to parse stand-alone RDF documents. In stand-alone RDF documents, the enclosing
	 * <tt>rdf:RDF</tt> root element is optional if this root element contains just one element (e.g.
	 * <tt>rdf:Description</tt>.
	 */
	public void setParseStandAloneDocuments(boolean standAloneDocs) {
		getParserConfig().set(XMLParserSettings.PARSE_STANDALONE_DOCUMENTS, standAloneDocs);
	}

	/**
	 * Returns whether the parser is currently in a mode to parse stand-alone RDF documents.
	 * 
	 * @see #setParseStandAloneDocuments
	 */
	public boolean getParseStandAloneDocuments() {
		return getParserConfig().get(XMLParserSettings.PARSE_STANDALONE_DOCUMENTS);
	}

	/**
	 * Parses the data from the supplied InputStream, using the supplied baseURI to resolve any relative URI
	 * references.
	 * 
	 * @param in
	 *        The InputStream from which to read the data, must not be <tt>null</tt>.
	 * @param baseURI
	 *        The URI associated with the data in the InputStream, must not be <tt>null</tt>.
	 * @throws IOException
	 *         If an I/O error occurred while data was read from the InputStream.
	 * @throws RDFParseException
	 *         If the parser has found an unrecoverable parse error.
	 * @throws RDFHandlerException
	 *         If the configured statement handler encountered an unrecoverable error.
	 * @throws IllegalArgumentException
	 *         If the supplied input stream or base URI is <tt>null</tt>.
	 */
	@Override
	public synchronized void parse(InputStream in, String baseURI)
		throws IOException,
		RDFParseException,
		RDFHandlerException
	{
		if (in == null) {
			throw new IllegalArgumentException("Input stream cannot be 'null'");
		}
		if (baseURI == null) {
			throw new IllegalArgumentException("Base URI cannot be 'null'");
		}

		InputSource inputSource = new InputSource(new BOMInputStream(in, false));
		inputSource.setSystemId(baseURI);

		parse(inputSource);
	}

	/**
	 * Parses the data from the supplied Reader, using the supplied baseURI to resolve any relative URI
	 * references.
	 * 
	 * @param reader
	 *        The Reader from which to read the data, must not be <tt>null</tt>.
	 * @param baseURI
	 *        The URI associated with the data in the InputStream, must not be <tt>null</tt>.
	 * @throws IOException
	 *         If an I/O error occurred while data was read from the InputStream.
	 * @throws RDFParseException
	 *         If the parser has found an unrecoverable parse error.
	 * @throws RDFHandlerException
	 *         If the configured statement handler has encountered an unrecoverable error.
	 * @throws IllegalArgumentException
	 *         If the supplied reader or base URI is <tt>null</tt>.
	 */
	@Override
	public synchronized void parse(Reader reader, String baseURI)
		throws IOException,
		RDFParseException,
		RDFHandlerException
	{
		if (reader == null) {
			throw new IllegalArgumentException("Reader cannot be 'null'");
		}
		if (baseURI == null) {
			throw new IllegalArgumentException("Base URI cannot be 'null'");
		}

		InputSource inputSource = new InputSource(reader);
		inputSource.setSystemId(baseURI);

		parse(inputSource);
	}

	private void parse(InputSource inputSource)
		throws IOException,
		RDFParseException,
		RDFHandlerException
	{
		clear();

		try {
			documentURI = inputSource.getSystemId();

			saxFilter.setParseStandAloneDocuments(
					getParserConfig().get(XMLParserSettings.PARSE_STANDALONE_DOCUMENTS));

			// saxFilter.clear();
			saxFilter.setDocumentURI(documentURI);

			XMLReader xmlReader = getXMLReader();
			xmlReader.setContentHandler(saxFilter);
			xmlReader.setErrorHandler(this);
			xmlReader.parse(inputSource);
		}
		catch (SAXParseException e) {
			Exception wrappedExc = e.getException();

			if (wrappedExc == null) {
				reportFatalError(e, e.getLineNumber(), e.getColumnNumber());
			}
			else {
				reportFatalError(wrappedExc, e.getLineNumber(), e.getColumnNumber());
			}
		}
		catch (SAXException e) {
			Exception wrappedExc = e.getException();

			if (wrappedExc == null) {
				reportFatalError(e);
			}
			else if (wrappedExc instanceof RDFParseException) {
				throw (RDFParseException)wrappedExc;
			}
			else if (wrappedExc instanceof RDFHandlerException) {
				throw (RDFHandlerException)wrappedExc;
			}
			else {
				reportFatalError(wrappedExc);
			}
		}
		finally {
			// Clean up
			saxFilter.clear();
			xmlLang = null;
			elementStack.clear();
			usedIDs.clear();
			clear();
		}
	}


	@Override
	public Collection<RioSetting<?>> getSupportedSettings() {
		// Override to add RDF/XML specific supported settings
		Set<RioSetting<?>> results = new HashSet<RioSetting<?>>(super.getSupportedSettings());

		results.addAll(getCompulsoryXmlPropertySettings());
		results.addAll(getCompulsoryXmlFeatureSettings());
		results.addAll(getOptionalXmlPropertySettings());
		results.addAll(getOptionalXmlFeatureSettings());

		results.add(XMLParserSettings.CUSTOM_XML_READER);
		results.add(XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID);
		results.add(XMLParserSettings.FAIL_ON_INVALID_NCNAME);
		results.add(XMLParserSettings.FAIL_ON_INVALID_QNAME);
		results.add(XMLParserSettings.FAIL_ON_MISMATCHED_TAGS);
		results.add(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
		results.add(XMLParserSettings.FAIL_ON_SAX_NON_FATAL_ERRORS);
		results.add(XMLParserSettings.PARSE_STANDALONE_DOCUMENTS);

		return results;
	}

	public SAXResult getSAXResult(String baseURI) {
		if (baseURI == null) {
			throw new IllegalArgumentException("Base URI cannot be 'null'");
		}
		documentURI = baseURI;
		saxFilter.setDocumentURI(baseURI);

		return new SAXResult(saxFilter);
	}

	void startDocument()
		throws RDFParseException,
		RDFHandlerException
	{
		if (rdfHandler != null) {
			rdfHandler.startRDF();
		}
	}

	void endDocument()
		throws RDFParseException,
		RDFHandlerException
	{
		if (rdfHandler != null) {
			rdfHandler.endRDF();
		}
	}

	/*-----------------------------*
	 * Methods called by SAXFilter *
	 *-----------------------------*/

	@Override
	protected void setBaseURI(ParsedURI baseURI) {
		// Note: we need to override this method to allow SAXFilter to access it
		super.setBaseURI(baseURI);
	}

	@Override
	protected void setBaseURI(String baseURI) {
		// Note: we need to override this method to allow SAXFilter to access it
		super.setBaseURI(baseURI);
	}

	void setXMLLang(String xmlLang) {
		if ("".equals(xmlLang)) {
			this.xmlLang = null;
		}
		else {
			this.xmlLang = xmlLang;
		}
	}

	void startElement(String namespaceURI, String localName, String qName, Atts atts)
		throws RDFParseException,
		RDFHandlerException
	{
		if (topIsProperty()) {
			// this element represents the subject and/or object of a statement
			processNodeElt(namespaceURI, localName, qName, atts, false);
		}
		else {
			// this element represents a property
			processPropertyElt(namespaceURI, localName, qName, atts, false);
		}
	}

	void endElement(String namespaceURI, String localName, String qName)
		throws RDFParseException,
		RDFHandlerException
	{
		Object topElement = peekStack(0);

		if (topElement instanceof NodeElement) {
			// Check if top node is 'volatile', meaning that it doesn't have a
			// start- and end element associated with it.
			if (((NodeElement)topElement).isVolatile()) {
				elementStack.pop();
			}
		}
		else {
			// topElement instanceof PropertyElement
			PropertyElement predicate = (PropertyElement)topElement;

			if (predicate.parseCollection()) {
				Resource lastListResource = predicate.getLastListResource();

				if (lastListResource == null) {
					// no last list resource, list must have been empty.
					NodeElement subject = (NodeElement)peekStack(1);

					reportStatement(subject.getResource(), predicate.getURI(), RDF.NIL);

					handleReification(RDF.NIL);
				}
				else {
					// Generate the final tail of the list.
					reportStatement(lastListResource, RDF.REST, RDF.NIL);
				}
			}

		}

		elementStack.pop();
	}

	void emptyElement(String namespaceURI, String localName, String qName, Atts atts)
		throws RDFParseException,
		RDFHandlerException
	{
		if (topIsProperty()) {
			// this element represents the subject and/or object of a statement
			processNodeElt(namespaceURI, localName, qName, atts, true);
		}
		else {
			// this element represents a property
			processPropertyElt(namespaceURI, localName, qName, atts, true);
		}
	}

	void text(String text)
		throws RDFParseException,
		RDFHandlerException
	{
		if (!topIsProperty()) {
			reportError("unexpected literal", XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
			return;
		}

		PropertyElement propEl = (PropertyElement)peekStack(0);
		IRI datatype = propEl.getDatatype();

		Literal lit = createLiteral(text, xmlLang, datatype);

		NodeElement subject = (NodeElement)peekStack(1);
		PropertyElement predicate = (PropertyElement)peekStack(0);

		reportStatement(subject.getResource(), predicate.getURI(), lit);

		handleReification(lit);
	}

	/*------------------------*
	 * RDF processing methods *
	 *------------------------*/

	/* Process a node element (can be both subject and object) */
	private void processNodeElt(String namespaceURI, String localName, String qName, Atts atts,
			boolean isEmptyElt)
		throws RDFParseException,
		RDFHandlerException
	{
		if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
			// Check the element name
			checkNodeEltName(namespaceURI, localName, qName);
		}

		Resource nodeResource = getNodeResource(atts);
		NodeElement nodeElement = new NodeElement(nodeResource);

		if (!elementStack.isEmpty()) {
			// node can be object of a statement, or part of an rdf:List
			NodeElement subject = (NodeElement)peekStack(1);
			PropertyElement predicate = (PropertyElement)peekStack(0);

			if (predicate.parseCollection()) {
				Resource lastListRes = predicate.getLastListResource();
				Resource newListRes = createNode();

				if (lastListRes == null) {
					// first element in the list
					reportStatement(subject.getResource(), predicate.getURI(), newListRes);

					handleReification(newListRes);
				}
				else {
					// not the first element in the list
					reportStatement(lastListRes, RDF.REST, newListRes);
				}

				reportStatement(newListRes, RDF.FIRST, nodeResource);

				predicate.setLastListResource(newListRes);
			}
			else {
				reportStatement(subject.getResource(), predicate.getURI(), nodeResource);

				handleReification(nodeResource);
			}
		}

		if (!localName.equals("Description") || !namespaceURI.equals(RDF.NAMESPACE)) {
			// element name is uri's type
			IRI className = null;
			if ("".equals(namespaceURI)) {
				// No namespace, use base URI
				className = buildResourceFromLocalName(localName);
			}
			else {
				className = createURI(namespaceURI + localName);
			}
			reportStatement(nodeResource, RDF.TYPE, className);
		}

		Att type = atts.removeAtt(RDF.NAMESPACE, "type");
		if (type != null) {
			// rdf:type attribute, value is a URI-reference
			IRI className = resolveURI(type.getValue());

			reportStatement(nodeResource, RDF.TYPE, className);
		}

		if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
			checkRDFAtts(atts);
		}

		processSubjectAtts(nodeElement, atts);

		if (!isEmptyElt) {
			elementStack.push(nodeElement);
		}
	}

	/**
	 * Retrieves the resource of a node element (subject or object) using relevant attributes (rdf:ID, rdf:about
	 * and rdf:nodeID) from its attributes list.
	 * 
	 * @return a resource or a bNode.
	 */
	private Resource getNodeResource(Atts atts)
		throws RDFParseException
	{
		Att id = atts.removeAtt(RDF.NAMESPACE, "ID");
		Att about = atts.removeAtt(RDF.NAMESPACE, "about");
		Att nodeID = atts.removeAtt(RDF.NAMESPACE, "nodeID");

		if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
			int definedAttsCount = 0;

			if (id != null) {
				definedAttsCount++;
			}
			if (about != null) {
				definedAttsCount++;
			}
			if (nodeID != null) {
				definedAttsCount++;
			}

			if (definedAttsCount > 1) {
				reportError("Only one of the attributes rdf:ID, rdf:about or rdf:nodeID can be used here",
						XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
			}
		}

		Resource result = null;

		if (id != null) {
			result = buildURIFromID(id.getValue());
		}
		else if (about != null) {
			result = resolveURI(about.getValue());
		}
		else if (nodeID != null) {
			result = createNode(nodeID.getValue());
		}
		else {
			// No resource specified, generate a bNode
			result = createNode();
		}

		return result;
	}

	/** processes subject attributes. */
	private void processSubjectAtts(NodeElement nodeElt, Atts atts)
		throws RDFParseException,
		RDFHandlerException
	{
		Resource subject = nodeElt.getResource();

		Iterator<Att> iter = atts.iterator();

		while (iter.hasNext()) {
			Att att = iter.next();

			IRI predicate = createURI(att.getURI());
			Literal lit = createLiteral(att.getValue(), xmlLang, null);

			reportStatement(subject, predicate, lit);
		}
	}

	private void processPropertyElt(String namespaceURI, String localName, String qName, Atts atts,
			boolean isEmptyElt)
		throws RDFParseException,
		RDFHandlerException
	{
		if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
			checkPropertyEltName(namespaceURI, localName, qName, XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
		}

		// Get the URI of the property
		IRI propURI = null;
		if (namespaceURI.equals("")) {
			// no namespace URI
			reportError("unqualified property element <" + qName + "> not allowed",
					XMLParserSettings.FAIL_ON_INVALID_QNAME);
			// Use base URI as namespace:
			propURI = buildResourceFromLocalName(localName);
		}
		else {
			propURI = createURI(namespaceURI + localName);
		}

		// List expansion rule
		if (propURI.equals(RDF.LI)) {
			NodeElement subject = (NodeElement)peekStack(0);
			propURI = createURI(RDF.NAMESPACE + "_" + subject.getNextLiCounter());
		}

		// Push the property on the stack.
		PropertyElement predicate = new PropertyElement(propURI);
		elementStack.push(predicate);

		// Check if property has a reification ID
		Att id = atts.removeAtt(RDF.NAMESPACE, "ID");
		if (id != null) {
			IRI reifURI = buildURIFromID(id.getValue());
			predicate.setReificationURI(reifURI);
		}

		// Check for presence of rdf:parseType attribute
		Att parseType = atts.removeAtt(RDF.NAMESPACE, "parseType");

		if (parseType != null) {
			if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
				checkNoMoreAtts(atts);
			}

			String parseTypeValue = parseType.getValue();

			if (parseTypeValue.equals("Resource")) {
				Resource objectResource = createNode();
				NodeElement subject = (NodeElement)peekStack(1);

				reportStatement(subject.getResource(), propURI, objectResource);

				if (isEmptyElt) {
					handleReification(objectResource);
				}
				else {
					NodeElement object = new NodeElement(objectResource);
					object.setIsVolatile(true);
					elementStack.push(object);
				}
			}
			else if (parseTypeValue.equals("Collection")) {
				if (isEmptyElt) {
					NodeElement subject = (NodeElement)peekStack(1);
					reportStatement(subject.getResource(), propURI, RDF.NIL);
					handleReification(RDF.NIL);
				}
				else {
					predicate.setParseCollection(true);
				}
			}
			else {
				// other parseType
				if (!parseTypeValue.equals("Literal")) {
					reportWarning("unknown parseType: " + parseType.getValue());
				}

				if (isEmptyElt) {
					NodeElement subject = (NodeElement)peekStack(1);

					Literal lit = createLiteral("", null, RDF.XMLLITERAL);

					reportStatement(subject.getResource(), propURI, lit);

					handleReification(lit);
				}
				else {
					// The next string is an rdf:XMLLiteral
					predicate.setDatatype(RDF.XMLLITERAL);

					saxFilter.setParseLiteralMode();
				}
			}
		}
		// parseType == null
		else if (isEmptyElt) {
			// empty element without an rdf:parseType attribute

			// Note: we handle rdf:datatype attributes here to allow datatyped
			// empty strings in documents. The current spec does have a
			// production rule that matches this, which is likely to be an
			// omission on its part.
			Att datatype = atts.getAtt(RDF.NAMESPACE, "datatype");

			if (atts.size() == 0 || atts.size() == 1 && datatype != null) {
				// element had no attributes, or only the optional
				// rdf:ID and/or rdf:datatype attributes.
				NodeElement subject = (NodeElement)peekStack(1);

				IRI dtURI = null;
				if (datatype != null) {
					dtURI = createURI(datatype.getValue());
				}

				Literal lit = createLiteral("", xmlLang, dtURI);

				reportStatement(subject.getResource(), propURI, lit);
				handleReification(lit);
			}
			else {
				// Create resource for the statement's object.
				Resource resourceRes = getPropertyResource(atts);

				// All special rdf attributes have been checked/removed.
				if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
					checkRDFAtts(atts);
				}

				NodeElement resourceElt = new NodeElement(resourceRes);
				NodeElement subject = (NodeElement)peekStack(1);

				reportStatement(subject.getResource(), propURI, resourceRes);
				handleReification(resourceRes);

				Att type = atts.removeAtt(RDF.NAMESPACE, "type");
				if (type != null) {
					// rdf:type attribute, value is a URI-reference
					IRI className = resolveURI(type.getValue());

					reportStatement(resourceRes, RDF.TYPE, className);
				}

				processSubjectAtts(resourceElt, atts);
			}
		}
		else {
			// Not an empty element, sub elements will follow.

			// Check for rdf:datatype attribute
			Att datatype = atts.removeAtt(RDF.NAMESPACE, "datatype");
			if (datatype != null) {
				IRI dtURI = resolveURI(datatype.getValue());
				predicate.setDatatype(dtURI);
			}

			// No more attributes are expected.
			if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
				checkNoMoreAtts(atts);
			}
		}

		if (isEmptyElt) {
			// Empty element has been pushed on the stack
			// at the start of this method, remove it.
			elementStack.pop();
		}
	}

	/**
	 * Retrieves the object resource of a property element using relevant attributes (rdf:resource and
	 * rdf:nodeID) from its attributes list.
	 * 
	 * @return a resource or a bNode.
	 */
	private Resource getPropertyResource(Atts atts)
		throws RDFParseException
	{
		Att resource = atts.removeAtt(RDF.NAMESPACE, "resource");
		Att nodeID = atts.removeAtt(RDF.NAMESPACE, "nodeID");

		if (getParserConfig().get(XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES)) {
			int definedAttsCount = 0;

			if (resource != null) {
				definedAttsCount++;
			}
			if (nodeID != null) {
				definedAttsCount++;
			}

			if (definedAttsCount > 1) {
				reportError("Only one of the attributes rdf:resource or rdf:nodeID can be used here",
						XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
			}
		}

		Resource result = null;

		if (resource != null) {
			result = resolveURI(resource.getValue());
		}
		else if (nodeID != null) {
			result = createNode(nodeID.getValue());
		}
		else {
			// No resource specified, generate a bNode
			result = createNode();
		}

		return result;
	}

	/*
	 * Processes any rdf:ID attributes that generate reified statements. This method assumes that a
	 * PropertyElement (which can have an rdf:ID attribute) is on top of the stack, and a NodeElement is below
	 * that.
	 */
	private void handleReification(Value value)
		throws RDFParseException,
		RDFHandlerException
	{
		PropertyElement predicate = (PropertyElement)peekStack(0);

		if (predicate.isReified()) {
			NodeElement subject = (NodeElement)peekStack(1);
			IRI reifRes = predicate.getReificationURI();
			reifyStatement(reifRes, subject.getResource(), predicate.getURI(), value);
		}
	}

	private void reifyStatement(Resource reifNode, Resource subj, IRI pred, Value obj)
		throws RDFParseException,
		RDFHandlerException
	{
		reportStatement(reifNode, RDF.TYPE, RDF.STATEMENT);
		reportStatement(reifNode, RDF.SUBJECT, subj);
		reportStatement(reifNode, RDF.PREDICATE, pred);
		reportStatement(reifNode, RDF.OBJECT, obj);
	}

	/**
	 * Builds a Resource from a non-qualified localname.
	 */
	private IRI buildResourceFromLocalName(String localName)
		throws RDFParseException
	{
		return resolveURI("#" + localName);
	}

	/**
	 * Builds a Resource from the value of an rdf:ID attribute.
	 */
	private IRI buildURIFromID(String id)
		throws RDFParseException
	{
		if (getParserConfig().get(XMLParserSettings.FAIL_ON_INVALID_NCNAME)) {
			// Check if 'id' is a legal NCName
			if (!XMLUtil.isNCName(id)) {
				reportError("Not an XML Name: " + id, XMLParserSettings.FAIL_ON_INVALID_NCNAME);
			}
		}

		IRI uri = resolveURI("#" + id);

		if (getParserConfig().get(XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID)) {
			// ID (URI) should be unique in the current document
			if (!usedIDs.add(uri)) {
				// URI was not added because the set already contained an equal
				// strings
				reportError("ID '" + id + "' has already been defined", XMLParserSettings.FAIL_ON_DUPLICATE_RDF_ID);
			}
		}

		return uri;
	}

	@Override
	protected Resource createNode(String nodeID)
		throws RDFParseException
	{
		if (getParserConfig().get(XMLParserSettings.FAIL_ON_INVALID_NCNAME)) {
			// Check if 'nodeID' is a legal NCName
			if (!XMLUtil.isNCName(nodeID)) {
				reportError("Not an XML Name: " + nodeID, XMLParserSettings.FAIL_ON_INVALID_NCNAME);
			}
		}

		return super.createNode(nodeID);
	}

	private Object peekStack(int distFromTop) {
		return elementStack.get(elementStack.size() - 1 - distFromTop);
	}

	private boolean topIsProperty() {
		return elementStack.isEmpty() || peekStack(0) instanceof PropertyElement;
	}

	/**
	 * Checks whether the node element name is from the RDF namespace and, if so, if it is allowed to be used in
	 * a node element. If the name is equal to one of the disallowed names (RDF, ID, about, parseType, resource,
	 * nodeID, datatype and li), an error is generated. If the name is not defined in the RDF namespace, but it
	 * claims that it is from this namespace, a warning is generated.
	 */
	private void checkNodeEltName(String namespaceURI, String localName, String qName)
		throws RDFParseException
	{
		if (RDF.NAMESPACE.equals(namespaceURI)) {

			if (localName.equals("Description") || localName.equals("Seq") || localName.equals("Bag")
					|| localName.equals("Alt") || localName.equals("Statement") || localName.equals("Property")
					|| localName.equals("List") || localName.equals("subject") || localName.equals("predicate")
					|| localName.equals("object") || localName.equals("type") || localName.equals("value")
					|| localName.equals("first") || localName.equals("rest") || localName.equals("nil")
					|| localName.startsWith("_"))
			{
				// These are OK
			}
			else if (localName.equals("li") || localName.equals("RDF") || localName.equals("ID")
					|| localName.equals("about") || localName.equals("parseType") || localName.equals("resource")
					|| localName.equals("nodeID") || localName.equals("datatype"))
			{
				reportError("<" + qName + "> not allowed as node element",
						XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
			}
			else if (localName.equals("bagID") || localName.equals("aboutEach")
					|| localName.equals("aboutEachPrefix"))
			{
				reportError(qName + " is no longer a valid RDF name",
						XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
			}
			else {
				reportWarning("unknown rdf element <" + qName + ">");
			}
		}
	}

	/**
	 * Checks whether the property element name is from the RDF namespace and, if so, if it is allowed to be
	 * used in a property element. If the name is equal to one of the disallowed names (RDF, ID, about,
	 * parseType, resource and li), an error is generated. If the name is not defined in the RDF namespace, but
	 * it claims that it is from this namespace, a warning is generated.
	 * 
	 * @param setting
	 */
	private void checkPropertyEltName(String namespaceURI, String localName, String qName,
			RioSetting<Boolean> setting)
		throws RDFParseException
	{
		if (RDF.NAMESPACE.equals(namespaceURI)) {

			if (localName.equals("li") || localName.equals("Seq") || localName.equals("Bag")
					|| localName.equals("Alt") || localName.equals("Statement") || localName.equals("Property")
					|| localName.equals("List") || localName.equals("subject") || localName.equals("predicate")
					|| localName.equals("object") || localName.equals("type") || localName.equals("value")
					|| localName.equals("first") || localName.equals("rest") || localName.equals("nil")
					|| localName.startsWith("_"))
			{
				// These are OK
			}
			else if (localName.equals("Description") || localName.equals("RDF") || localName.equals("ID")
					|| localName.equals("about") || localName.equals("parseType") || localName.equals("resource")
					|| localName.equals("nodeID") || localName.equals("datatype"))
			{
				reportError("<" + qName + "> not allowed as property element", setting);
			}
			else if (localName.equals("bagID") || localName.equals("aboutEach")
					|| localName.equals("aboutEachPrefix"))
			{
				reportError(qName + " is no longer a valid RDF name", setting);
			}
			else {
				reportWarning("unknown rdf element <" + qName + ">");
			}
		}
	}

	/**
	 * Checks whether 'atts' contains attributes from the RDF namespace that are not allowed as attributes. If
	 * such an attribute is found, an error is generated and the attribute is removed from 'atts'. If the
	 * attribute is not defined in the RDF namespace, but it claims that it is from this namespace, a warning is
	 * generated.
	 */
	private void checkRDFAtts(Atts atts)
		throws RDFParseException
	{
		Iterator<Att> iter = atts.iterator();

		while (iter.hasNext()) {
			Att att = iter.next();

			if (RDF.NAMESPACE.equals(att.getNamespace())) {
				String localName = att.getLocalName();

				if (localName.equals("Seq") || localName.equals("Bag") || localName.equals("Alt")
						|| localName.equals("Statement") || localName.equals("Property") || localName.equals("List")
						|| localName.equals("subject") || localName.equals("predicate") || localName.equals("object")
						|| localName.equals("type") || localName.equals("value") || localName.equals("first")
						|| localName.equals("rest") || localName.equals("nil") || localName.startsWith("_"))
				{
					// These are OK
				}
				else if (localName.equals("Description") || localName.equals("li") || localName.equals("RDF")
						|| localName.equals("ID") || localName.equals("about") || localName.equals("parseType")
						|| localName.equals("resource") || localName.equals("nodeID") || localName.equals("datatype"))
				{
					reportError("'" + att.getQName() + "' not allowed as attribute name",
							XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
					iter.remove();
				}
				else if (localName.equals("bagID") || localName.equals("aboutEach")
						|| localName.equals("aboutEachPrefix"))
				{
					reportError(att.getQName() + " is no longer a valid RDF name",
							XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
				}
				else {
					reportWarning("unknown rdf attribute '" + att.getQName() + "'");
				}
			}
		}
	}

	/**
	 * Checks whether 'atts' is empty. If this is not the case, a warning is generated for each attribute that
	 * is still present.
	 */
	private void checkNoMoreAtts(Atts atts)
		throws RDFParseException
	{
		if (atts.size() > 0) {
			Iterator<Att> iter = atts.iterator();

			while (iter.hasNext()) {
				Att att = iter.next();

				reportError("unexpected attribute '" + att.getQName() + "'",
						XMLParserSettings.FAIL_ON_NON_STANDARD_ATTRIBUTES);
				iter.remove();
			}
		}
	}

	/**
	 * Reports a stament to the configured RDFHandlerException.
	 * 
	 * @param subject
	 *        The statement's subject.
	 * @param predicate
	 *        The statement's predicate.
	 * @param object
	 *        The statement's object.
	 * @throws RDFHandlerException
	 *         If the configured RDFHandlerException throws an RDFHandlerException.
	 */
	private void reportStatement(Resource subject, IRI predicate, Value object)
		throws RDFParseException,
		RDFHandlerException
	{
		Statement st = createStatement(subject, predicate, object);
		if (rdfHandler != null) {
			rdfHandler.handleStatement(st);
		}
	}

	@Override
	protected Literal createLiteral(String label, String lang, IRI datatype)
		throws RDFParseException
	{
		Locator locator = saxFilter.getLocator();
		if (locator != null) {
			return createLiteral(label, lang, datatype, locator.getLineNumber(), locator.getColumnNumber());
		}
		else {
			return createLiteral(label, lang, datatype, -1, -1);
		}
	}

	/**
	 * Overrides {@link AbstractRDFParser#reportWarning(String)}, adding line- and column number information to
	 * the error.
	 */
	@Override
	protected void reportWarning(String msg) {
		Locator locator = saxFilter.getLocator();
		if (locator != null) {
			reportWarning(msg, locator.getLineNumber(), locator.getColumnNumber());
		}
		else {
			reportWarning(msg, -1, -1);
		}
	}

	/**
	 * Overrides {@link AbstractRDFParser#reportError(String, RioSetting)}, adding line- and column number
	 * information to the error.
	 */
	@Override
	protected void reportError(String msg, RioSetting<Boolean> setting)
		throws RDFParseException
	{
		Locator locator = saxFilter.getLocator();
		if (locator != null) {
			reportError(msg, locator.getLineNumber(), locator.getColumnNumber(), setting);
		}
		else {
			reportError(msg, -1, -1, setting);
		}
	}

	/**
	 * Overrides {@link AbstractRDFParser#reportError(String, RioSetting)}, adding line- and column number
	 * information to the error.
	 */
	@Override
	protected void reportError(Exception e, RioSetting<Boolean> setting)
		throws RDFParseException
	{
		Locator locator = saxFilter.getLocator();
		if (locator != null) {
			reportError(e, locator.getLineNumber(), locator.getColumnNumber(), setting);
		}
		else {
			reportError(e, -1, -1, setting);
		}
	}

	/**
	 * Overrides {@link AbstractRDFParser#reportFatalError(String)}, adding line- and column number information
	 * to the error.
	 */
	@Override
	protected void reportFatalError(String msg)
		throws RDFParseException
	{
		Locator locator = saxFilter.getLocator();
		if (locator != null) {
			reportFatalError(msg, locator.getLineNumber(), locator.getColumnNumber());
		}
		else {
			reportFatalError(msg, -1, -1);
		}
	}

	/**
	 * Overrides {@link AbstractRDFParser#reportFatalError(Exception)}, adding line- and column number
	 * information to the error.
	 */
	@Override
	protected void reportFatalError(Exception e)
		throws RDFParseException
	{
		Locator locator = saxFilter.getLocator();
		if (locator != null) {
			reportFatalError(e, locator.getLineNumber(), locator.getColumnNumber());
		}
		else {
			reportFatalError(e, -1, -1);
		}
	}

	/*-----------------------------------------------*
	 * Inner classes NodeElement and PropertyElement *
	 *-----------------------------------------------*/

	static class NodeElement {

		private Resource resource;

		private boolean isVolatile = false;;

		private int liCounter = 1;

		public NodeElement(Resource resource) {
			this.resource = resource;
		}

		public Resource getResource() {
			return resource;
		}

		public void setIsVolatile(boolean isVolatile) {
			this.isVolatile = isVolatile;
		}

		public boolean isVolatile() {
			return isVolatile;
		}

		public int getNextLiCounter() {
			return liCounter++;
		}
	}

	static class PropertyElement {

		/** The property URI. */
		private IRI uri;

		/** An optional reification identifier. */
		private IRI reificationURI;

		/** An optional datatype. */
		private IRI datatype;

		/**
		 * Flag indicating whether this PropertyElement has an attribute <tt>rdf:parseType="Collection"</tt>.
		 */
		private boolean parseCollection = false;

		/**
		 * The resource that was used to append the last part of an rdf:List.
		 */
		private Resource lastListResource;

		public PropertyElement(IRI uri) {
			this.uri = uri;
		}

		public IRI getURI() {
			return uri;
		}

		public boolean isReified() {
			return reificationURI != null;
		}

		public void setReificationURI(IRI reifURI) {
			this.reificationURI = reifURI;
		}

		public IRI getReificationURI() {
			return reificationURI;
		}

		public void setDatatype(IRI datatype) {
			this.datatype = datatype;
		}

		public IRI getDatatype() {
			return datatype;
		}

		public boolean parseCollection() {
			return parseCollection;
		}

		public void setParseCollection(boolean parseCollection) {
			this.parseCollection = parseCollection;
		}

		public Resource getLastListResource() {
			return lastListResource;
		}

		public void setLastListResource(Resource resource) {
			lastListResource = resource;
		}
	}

	/**
	 * Implementation of SAX ErrorHandler.warning
	 */
	@Override
	public void warning(SAXParseException exception)
		throws SAXException
	{
		this.reportWarning(exception.getMessage());
	}

	/**
	 * Implementation of SAX ErrorHandler.error
	 */
	@Override
	public void error(SAXParseException exception)
		throws SAXException
	{
		try {
			this.reportError(exception, XMLParserSettings.FAIL_ON_SAX_NON_FATAL_ERRORS);
		}
		catch (RDFParseException rdfpe) {
			throw new SAXException(rdfpe);
		}
	}

	/**
	 * Implementation of SAX ErrorHandler.fatalError
	 */
	@Override
	public void fatalError(SAXParseException exception)
		throws SAXException
	{
		try {
			this.reportFatalError(exception);
		}
		catch (RDFParseException rdfpe) {
			throw new SAXException(rdfpe);
		}
	}
}
