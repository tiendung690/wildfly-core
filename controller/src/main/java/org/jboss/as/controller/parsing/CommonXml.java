/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller.parsing;

import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static org.jboss.as.controller.parsing.ParseUtils.invalidAttributeValue;
import static org.jboss.as.controller.parsing.ParseUtils.missingRequired;
import static org.jboss.as.controller.parsing.ParseUtils.parseBoundedIntegerAttribute;
import static org.jboss.as.controller.parsing.ParseUtils.readStringAttributeElement;
import static org.jboss.as.controller.parsing.ParseUtils.requireAttributes;
import static org.jboss.as.controller.parsing.ParseUtils.requireNoAttributes;
import static org.jboss.as.controller.parsing.ParseUtils.requireNoContent;
import static org.jboss.as.controller.parsing.ParseUtils.requireSingleAttribute;
import static org.jboss.as.controller.parsing.ParseUtils.unexpectedAttribute;
import static org.jboss.as.controller.parsing.ParseUtils.unexpectedElement;
import static org.jboss.as.controller.parsing.ParseUtils.unexpectedEndElement;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.controller.NewExtension;
import org.jboss.as.controller.NewExtensionContext;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleLoader;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLElementWriter;
import org.jboss.staxmapper.XMLExtendedStreamReader;
import org.jboss.staxmapper.XMLExtendedStreamWriter;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public abstract class CommonXml implements XMLElementReader<List<ModelNode>>, XMLElementWriter<ModelNode> {

    /** The restricted path names. */
    protected static final Set<String> RESTRICTED_PATHS;

    static {
        final HashSet<String> set = new HashSet<String>(10);
        // Define the restricted path names.
        set.add("jboss.home");
        set.add("jboss.home.dir");
        set.add("user.home");
        set.add("user.dir");
        set.add("java.home");
        set.add("jboss.server.base.dir");
        set.add("jboss.server.data.dir");
        set.add("jboss.server.log.dir");
        set.add("jboss.server.tmp.dir");
        // NOTE we actually don't create services for the following
        // however the names remain restricted for use in the configuration
        set.add("jboss.modules.dir");
        set.add("jboss.server.deploy.dir");
        set.add("jboss.domain.servers.dir");
        RESTRICTED_PATHS = Collections.unmodifiableSet(set);
    }

    protected final ModuleLoader moduleLoader;
    protected final NewExtensionContext extensionContext;

    protected CommonXml(final ModuleLoader loader, final NewExtensionContext context) {
        moduleLoader = loader;
        extensionContext = context;
    }

    protected void parseNamespaces(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> nodes) {
        final int namespaceCount = reader.getNamespaceCount();
        for (int i = 0; i < namespaceCount; i ++) {
            final ModelNode operation = new ModelNode();
            operation.get("address").set(address);
            operation.get("operation").add("add-namespace");
            operation.get("namespace").add(reader.getNamespacePrefix(i), reader.getNamespaceURI(i));
            nodes.add(operation);
        }
    }

    @SuppressWarnings("unused")
    protected void readHeadComment(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> nodes) throws XMLStreamException {
        // TODO STXM-6
    }

    @SuppressWarnings("unused")
    protected void readTailComment(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> nodes) throws XMLStreamException {
        // TODO STXM-6
    }

    protected void parseSchemaLocations(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> updateList, final int idx) throws XMLStreamException {
        final List<String> values = reader.getListAttributeValue(idx);
        if ((values.size() & 1) != 0) {
            throw invalidAttributeValue(reader, idx);
        }
        final Iterator<String> it = values.iterator();
        while (it.hasNext()) {
            final ModelNode update = new ModelNode();
            update.get("address").set(address);
            update.get("operation").set("add-schema-location");
            update.get("namespace-uri").set(it.next());
            update.get("location-uri").set(it.next());
            updateList.add(update);
        }
    }

    protected void writeSchemaLocation(final XMLExtendedStreamWriter writer, final ModelNode modelNode) throws XMLStreamException {
        final StringBuilder b = new StringBuilder();
        final Iterator<ModelNode> iterator = modelNode.get("schema-location").asList().iterator();
        while (iterator.hasNext()) {
            final ModelNode location = iterator.next();
            final Property property = location.asProperty();
            b.append(property.getName()).append(' ').append(property.getValue().asString());
            if (iterator.hasNext()) {
                b.append(' ');
            }
        }
        if (b.length() > 0) {
            writer.writeAttribute(Namespace.XML_SCHEMA_INSTANCE.getUriString(), Attribute.SCHEMA_LOCATION.getLocalName(), b.toString());
        }
    }

    protected void writeNamespaces(final XMLExtendedStreamWriter writer, final ModelNode modelNode) throws XMLStreamException {
        for (final ModelNode namespace : modelNode.get("namespace").asList()) {
            final Property property = namespace.asProperty();
            writer.writeNamespace(property.getName(), property.getValue().asString());
        }
    }

    protected void parseExtensions(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> list) throws XMLStreamException {
        requireNoAttributes(reader);

        final Set<String> found = new HashSet<String>();

        final ExtensionParsingContextImpl context = new ExtensionParsingContextImpl(reader.getXMLMapper());

        while (reader.nextTag() != END_ELEMENT) {
            // Attribute && require no content
            final String moduleName = readStringAttributeElement(reader, Attribute.MODULE.getLocalName());

            if (! found.add(moduleName)) {
                // duplicate module name
                throw invalidAttributeValue(reader, 0);
            }

            // Register element handlers for this extension
            try {
                final Module module = moduleLoader.loadModule(ModuleIdentifier.fromString(moduleName));
                boolean initialized = false;
                for (final NewExtension extension : module.loadService(NewExtension.class)) {
                    extension.initializeParsers(context);
                    if (!initialized) {
                        initialized = true;
                    }
                }
                if (!initialized) {
                    throw new IllegalStateException("No META-INF/services/" + NewExtension.class.getName() + " found for " + module.getIdentifier());
                }
                final ModelNode add = new ModelNode();
                add.get("address").set(address).add("extension", moduleName);
                add.get("operation").set("add-extension");
                list.add(add);
            } catch (final ModuleLoadException e) {
                throw new XMLStreamException("Failed to load module", e);
            }
        }
    }

    protected void parsePaths(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> list, final boolean requirePath) throws XMLStreamException {
        final Set<String> pathNames = new HashSet<String>();
        while (reader.hasNext() && reader.nextTag() != END_ELEMENT) {
            switch (Namespace.forUri(reader.getNamespaceURI())) {
                case DOMAIN_1_0: {
                    final Element element = Element.forName(reader.getLocalName());
                    switch (element) {
                        case PATH: {
                            parsePath(reader, address, list, requirePath, pathNames);
                            break;
                        } default: {
                            throw unexpectedElement(reader);
                        }
                    }
                    break;
                } default: {
                    throw unexpectedElement(reader);
                }
            }
        }
    }

    protected void parsePath(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> list, final boolean requirePath, final Set<String> defined) throws XMLStreamException {
        String name = null;
        String path = null;
        String relativeTo = null;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                switch (attribute) {
                    case NAME: {
                        name = value.trim();
                        if (RESTRICTED_PATHS.contains(value)) {
                            throw new XMLStreamException(name + " is reserved", reader.getLocation());
                        }
                        if(! defined.add(name)) {
                            throw new XMLStreamException(name + " already defined", reader.getLocation());
                        }
                        break;
                    } case PATH: {
                        path = value;
                        break;
                    }
                    case RELATIVE_TO: {
                        relativeTo = value;
                        break;
                    }
                    default: {
                        throw unexpectedAttribute(reader, i);
                    }
                }
            }
        }
        if(name == null) {
            throw missingRequired(reader, Collections.singleton(Attribute.NAME));
        }
        if(requirePath && path == null) {
            throw missingRequired(reader, Collections.singleton(Attribute.PATH));
        }
        requireNoContent(reader);
        final ModelNode update = new ModelNode();
        update.get("address").set(address);
        update.get("operation").set("update-path");
        update.get("name").set(name);
        update.get("path").set(path);
        if (relativeTo != null) update.get("relativeTo").set(relativeTo);
        list.add(update);
    }

    protected void parseSystemProperties(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> list) throws XMLStreamException {
        parseProperties(reader, "set-system-properties", address, list);
    }

    protected void parseEnvironmentVariables(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> list) throws XMLStreamException {
        parseProperties(reader, "set-environment-variables", address, list);
    }

    private void parseProperties(final XMLExtendedStreamReader reader, final String operationName, final ModelNode address, final List<ModelNode> list) throws XMLStreamException {
        final ModelNode systemPropertySet = new ModelNode();
        systemPropertySet.get("address").set(address);
        systemPropertySet.get("operation").set(operationName);
        final ModelNode properties = systemPropertySet.get("properties").setEmptyObject();
        while (reader.nextTag() != END_ELEMENT) {
            if (Namespace.forUri(reader.getNamespaceURI()) != Namespace.DOMAIN_1_0) {
                throw unexpectedElement(reader);
            }
            if (Element.forName(reader.getLocalName()) != Element.PROPERTY) {
                throw unexpectedElement(reader);
            }
            final String[] array = requireAttributes(reader, Attribute.NAME.getLocalName(), Attribute.VALUE.getLocalName());
            properties.get(array[0]).set(array[1]);
        }
        list.add(systemPropertySet);
    }

    protected void parseManagement(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> list) throws XMLStreamException {
        // Handle attributes
        String interfaceName = null;
        int port = 0;
        int maxThreads = -1;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                switch (attribute) {
                    case INTERFACE: {
                        interfaceName = value;
                        break;
                    }
                    case PORT: {
                        port = Integer.parseInt(value);
                        if (port < 0) {
                            throw new XMLStreamException("Illegal '" + attribute.getLocalName() +
                                    "' value " + port + " -- cannot be negative",
                                    reader.getLocation());
                        }
                        break;
                    }
                    case MAX_THREADS: {
                        maxThreads = Integer.parseInt(value);
                        if (maxThreads < 1) {
                            throw new XMLStreamException("Illegal '" + attribute.getLocalName() +
                                    "' value " + maxThreads + " -- must be greater than 0",
                                    reader.getLocation());
                        }
                        break;
                    }
                    default:
                        throw unexpectedAttribute(reader, i);
                }
            }
        }
        if (interfaceName == null) {
            throw missingRequired(reader, Collections.singleton(Attribute.INTERFACE.getLocalName()));
        }
        final ModelNode addMgmt = new ModelNode();
        addMgmt.get("address").set(address);
        addMgmt.get("operation").set("add-management");
        addMgmt.get("interface-name").set(interfaceName);
        addMgmt.get("port").set(port);
        list.add(addMgmt);

        if (maxThreads > 0) {
            // TODO - this is non-optimal.
            final ModelNode setSocketThreads = new ModelNode();
            setSocketThreads.get("address").set(address);
            setSocketThreads.get("operation").set("set-management-socket-threads");
            setSocketThreads.get("max-threads").set(maxThreads);
            list.add(setSocketThreads);
        }
        reader.discardRemainder();

    }

    protected void parseJvm(final XMLExtendedStreamReader reader, final ModelNode parentAddress, final List<ModelNode> updates, final Set<String> jvmNames) throws XMLStreamException {

        // Handle attributes
        final List<ModelNode> attrUpdates = new ArrayList<ModelNode>();
        String name = null;
        String type = JvmType.SUN.toString();
        String home = null;
        Boolean debugEnabled = null;
        String debugOptions = null;
        Boolean envClasspathIgnored = null;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw ParseUtils.unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                switch (attribute) {
                    case NAME: {
                        if (name != null)
                            throw ParseUtils.duplicateAttribute(reader, attribute.getLocalName());

                        if (!jvmNames.add(value)) {
                            throw new XMLStreamException("Duplicate JVM declaration " + value, reader.getLocation());
                        }
                        name = value;
                        break;
                    }
                    case JAVA_HOME: {
                        if (home != null)
                            throw ParseUtils.duplicateAttribute(reader, attribute.getLocalName());
                        home = value;
                        final ModelNode update = new ModelNode();
                        update.get("operation").set("java-home");
                        update.get("java-home").set(home);
                        attrUpdates.add(update);
                        break;
                    }
                    case TYPE: {
                        try {
                            // Validate the type against the enum
                            Enum.valueOf(JvmType.class, value);
                            type = value;
                        } catch (final IllegalArgumentException e) {
                            throw ParseUtils.invalidAttributeValue(reader, i);
                        }
                        break;
                    }
                    case DEBUG_ENABLED: {
                        if (debugEnabled != null)
                            throw ParseUtils.duplicateAttribute(reader, attribute.getLocalName());
                        debugEnabled = Boolean.valueOf(value);
                        final ModelNode update = new ModelNode();
                        update.get("operation").set("set-debug-enabled");
                        update.get("enabled").set(debugEnabled);
                        attrUpdates.add(update);
                        break;
                    }
                    case DEBUG_OPTIONS: {
                        if (debugOptions != null)
                            throw ParseUtils.duplicateAttribute(reader, attribute.getLocalName());
                        debugOptions = value;
                        final ModelNode update = new ModelNode();
                        update.get("operation").set("set-debug-options");
                        update.get("debug-options").set(debugOptions);
                        attrUpdates.add(update);
                        break;
                    }
                    case ENV_CLASSPATH_IGNORED: {
                        if (envClasspathIgnored != null)
                            throw ParseUtils.duplicateAttribute(reader, attribute.getLocalName());
                        envClasspathIgnored = Boolean.valueOf(value);
                        final ModelNode update = new ModelNode();
                        update.get("operation").set("set-env-classpath-ignored");
                        update.get("env-classpath-ignored").set(envClasspathIgnored);
                        attrUpdates.add(update);
                        break;
                    }
                    default:
                        throw ParseUtils.unexpectedAttribute(reader, i);
                }
            }
        }
        if (name == null) {
            // FIXME and fix xsd. A name is only required at domain and host
            // level (i.e. when wrapped in <jvms/>). At server-group and server
            // levels it can be unnamed, in which case configuration from
            // domain and host levels aren't mixed in. OR make name required in xsd always
            throw ParseUtils.missingRequired(reader, Collections.singleton(Attribute.NAME));
        }

        final ModelNode address = jvmNames == null ? parentAddress : parentAddress.clone().add(ModelDescriptionConstants.JVM, name);

        final ModelNode addUpdate = new ModelNode();
        addUpdate.get("address").set(address);
        addUpdate.get("operation").set("add");
        addUpdate.get("name").set(name);
        addUpdate.get("jvm-type").set(type);
        updates.add(addUpdate);

        // Now we've done the add and we know the address
        for (final ModelNode attrUpdate : attrUpdates) {
            attrUpdate.get("address").set(address);
            updates.add(attrUpdate);
        }

        // Handle elements
        boolean hasJvmOptions = false;
        boolean hasEnvironmentVariables = false;
        boolean hasSystemProperties = false;
        while (reader.hasNext() && reader.nextTag() != END_ELEMENT) {
            switch (Namespace.forUri(reader.getNamespaceURI())) {
                case DOMAIN_1_0: {
                    final Element element = Element.forName(reader.getLocalName());
                    switch (element) {
                        case HEAP: {
                            parseHeap(reader, address, updates);
                            break;
                        }
                        case PERMGEN: {
                            parsePermgen(reader, address, updates);
                            break;
                        }
                        case STACK: {
                            parseStack(reader, address, updates);
                            break;
                        }
                        case AGENT_LIB: {
                            parseAgentLib(reader, address, updates);
                            break;
                        }
                        case AGENT_PATH: {
                            parseAgentPath(reader, address, updates);
                            break;
                        }
                        case JAVA_AGENT: {
                            parseJavaagent(reader, address, updates);
                            break;
                        }
                        case ENVIRONMENT_VARIABLES: {
                            if (hasEnvironmentVariables) {
                                throw new XMLStreamException(element.getLocalName() + " already declared", reader.getLocation());
                            }
                            parseEnvironmentVariables(reader, address, updates);
                            hasEnvironmentVariables = true;
                            break;
                        }
                        case SYSTEM_PROPERTIES: {
                            if (hasSystemProperties) {
                                throw new XMLStreamException(element.getLocalName() + " already declared", reader.getLocation());
                            }
                            parseSystemProperties(reader, address, updates);
                            hasSystemProperties = true;
                            break;
                        }
                        case JVM_OPTIONS: {
                            if (hasJvmOptions) {
                                throw new XMLStreamException(element.getLocalName() + " already declared", reader.getLocation());
                            }
                            parseJvmOptions(reader, address, updates);
                            hasJvmOptions = true;
                            break;
                        }
                        default:
                            throw ParseUtils.unexpectedElement(reader);
                    }
                    break;
                }
                default:
                    throw ParseUtils.unexpectedElement(reader);
            }
        }
    }

    private void parseHeap(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> updates) throws XMLStreamException {

        // Handle attributes
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw ParseUtils.unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                switch (attribute) {
                    case SIZE: {
                        final ModelNode update = new ModelNode();
                        update.get("address").set(address);
                        update.get("operation").set("set-heap-size");
                        update.get("size").set(value);
                        updates.add(update);
                        break;
                    }
                    case MAX_SIZE: {
                        final ModelNode update = new ModelNode();
                        update.get("address").set(address);
                        update.get("operation").set("set-max-heap-size");
                        update.get("size").set(value);
                        updates.add(update);
                        break;
                    }
                    default:
                        throw ParseUtils.unexpectedAttribute(reader, i);
                }
            }
        }
        // Handle elements
        ParseUtils.requireNoContent(reader);
    }

    private void parsePermgen(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> updates) throws XMLStreamException {

        // Handle attributes
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw ParseUtils.unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                switch (attribute) {
                    case SIZE: {
                        final ModelNode update = new ModelNode();
                        update.get("address").set(address);
                        update.get("operation").set("set-permgen-size");
                        update.get("size").set(value);
                        updates.add(update);
                        break;
                    }
                    case MAX_SIZE: {
                        final ModelNode update = new ModelNode();
                        update.get("address").set(address);
                        update.get("operation").set("set-max-permgen-size");
                        update.get("size").set(value);
                        updates.add(update);
                        break;
                    }
                    default:
                        throw ParseUtils.unexpectedAttribute(reader, i);
                }
            }
        }
        // Handle elements
        ParseUtils.requireNoContent(reader);
    }

    private void parseStack(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> updates) throws XMLStreamException {

        // Handle attributes
        boolean sizeSet = false;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw ParseUtils.unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                switch (attribute) {
                    case SIZE: {
                        final ModelNode update = new ModelNode();
                        update.get("address").set(address);
                        update.get("operation").set("set-stack-size");
                        update.get("size").set(value);
                        updates.add(update);
                        sizeSet = true;
                        break;
                    }
                    default:
                        throw ParseUtils.unexpectedAttribute(reader, i);
                }
            }
        }
        if (!sizeSet) {
            throw ParseUtils.missingRequired(reader, Collections.singleton(Attribute.SIZE));
        }
        // Handle elements
        ParseUtils.requireNoContent(reader);
    }

    private void parseAgentLib(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> updates) throws XMLStreamException {

        // Handle attributes
        boolean valueSet = false;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw ParseUtils.unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                switch (attribute) {
                    case VALUE: {
                        final ModelNode update = new ModelNode();
                        update.get("address").set(address);
                        update.get("operation").set("set-agent-lib");
                        update.get("agent-lib").set(value);
                        updates.add(update);
                        valueSet = true;
                        break;
                    }
                    default:
                        throw ParseUtils.unexpectedAttribute(reader, i);
                }
            }
        }
        if (!valueSet) {
            throw ParseUtils.missingRequired(reader, Collections.singleton(Attribute.VALUE));
        }
        // Handle elements
        ParseUtils.requireNoContent(reader);
    }

    private void parseAgentPath(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> updates) throws XMLStreamException {

        // Handle attributes
        boolean valueSet = false;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw ParseUtils.unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                switch (attribute) {
                    case VALUE: {
                        final ModelNode update = new ModelNode();
                        update.get("address").set(address);
                        update.get("operation").set("set-agent-path");
                        update.get("agent-path").set(value);
                        updates.add(update);
                        valueSet = true;
                        break;
                    }
                    default:
                        throw ParseUtils.unexpectedAttribute(reader, i);
                }
            }
        }
        if (!valueSet) {
            throw ParseUtils.missingRequired(reader, Collections.singleton(Attribute.VALUE));
        }
        // Handle elements
        ParseUtils.requireNoContent(reader);
    }

    private void parseJavaagent(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> updates) throws XMLStreamException {

        // Handle attributes
        boolean valueSet = false;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw ParseUtils.unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                switch (attribute) {
                    case VALUE: {
                        final ModelNode update = new ModelNode();
                        update.get("address").set(address);
                        update.get("operation").set("set-java-agent");
                        update.get("java-agent").set(value);
                        updates.add(update);
                        valueSet = true;
                        break;
                    }
                    default:
                        throw ParseUtils.unexpectedAttribute(reader, i);
                }
            }
        }
        if (!valueSet) {
            throw ParseUtils.missingRequired(reader, Collections.singleton(Attribute.VALUE));
        }
        // Handle elements
        ParseUtils.requireNoContent(reader);
    }

    private void parseJvmOptions(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> updates) throws XMLStreamException {

        // Handle attributes
        ParseUtils.requireNoAttributes(reader);
        // Handle elements
        boolean optionSet = false;
        while (reader.hasNext() && reader.nextTag() != END_ELEMENT) {
            switch (Namespace.forUri(reader.getNamespaceURI())) {
                case DOMAIN_1_0: {
                    final Element element = Element.forName(reader.getLocalName());
                    if (element == Element.OPTION) {
                        // Handle attributes
                        String option = null;
                        final int count = reader.getAttributeCount();
                        for (int i = 0; i < count; i++) {
                            final String attrValue = reader.getAttributeValue(i);
                            if (reader.getAttributeNamespace(i) != null) {
                                throw ParseUtils.unexpectedAttribute(reader, i);
                            }
                            else {
                                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                                switch (attribute) {
                                    case VALUE: {
                                        option = attrValue;
                                        break;
                                    }
                                    default:
                                        throw ParseUtils.unexpectedAttribute(reader, i);
                                }
                            }
                        }
                        if (option == null) {
                            throw ParseUtils.missingRequired(reader, Collections.singleton(Attribute.NAME));
                        }

                        // PropertyAdd
                        final ModelNode update = new ModelNode();
                        update.get("address").set(address);
                        update.get("operation").set("add-jvm-option");
                        update.get("option").set(option);
                        updates.add(update);
                        optionSet = true;
                        // Handle elements
                        ParseUtils.requireNoContent(reader);
                    } else {
                        throw ParseUtils.unexpectedElement(reader);
                    }
                    break;
                }
                default:
                    throw ParseUtils.unexpectedElement(reader);
            }
        }
        if (!optionSet) {
            throw ParseUtils.missingRequiredElement(reader, Collections.singleton(Element.OPTION));
        }
    }

    protected void parseInterfaceCriteria(final XMLExtendedStreamReader reader, final ModelNode criteria) throws XMLStreamException {
        // all subsequent elements are criteria elements
        if (reader.nextTag() == END_ELEMENT) {
            return;
        }
        if (Namespace.forUri(reader.getNamespaceURI()) != Namespace.DOMAIN_1_0) {
            throw unexpectedElement(reader);
        }
        final Element element = Element.forName(reader.getLocalName());
        switch (element) {
            case ANY_ADDRESS:
            case ANY_IPV4_ADDRESS:
            case ANY_IPV6_ADDRESS: {
                criteria.set(element.getLocalName());
                requireNoContent(reader); // consume this element
                requireNoContent(reader); // consume rest of criteria (no further content allowed)
                return;
            }
        }
        do {
            switch (element) {
                case ANY: parseCompoundInterfaceCriterion(reader, criteria.add().set("any", new ModelNode()).get("any")); break;
                case NOT: parseCompoundInterfaceCriterion(reader, criteria.add().set("not", new ModelNode()).get("not")); break;
                default: {
                    parseSimpleInterfaceCriterion(reader, criteria.add().set(element.getLocalName(), new ModelNode()).get(element.getLocalName()));
                    break;
                }
            }
        } while (reader.nextTag() != END_ELEMENT);
    }

    protected void parseCompoundInterfaceCriterion(final XMLExtendedStreamReader reader, final ModelNode criterion) throws XMLStreamException {
        requireNoAttributes(reader);
        while (reader.nextTag() != END_ELEMENT) {
            parseSimpleInterfaceCriterion(reader, criterion.add());
        }
    }

    /**
     * Creates the appropriate AbstractInterfaceCriteriaElement for simple criterion.
     *
     * @throws javax.xml.stream.XMLStreamException if an error occurs
     */
    protected void parseSimpleInterfaceCriterion(final XMLExtendedStreamReader reader, final ModelNode criteria) throws XMLStreamException {
        if (Namespace.forUri(reader.getNamespaceURI()) != Namespace.DOMAIN_1_0) {
            throw unexpectedElement(reader);
        }
        final Element element = Element.forName(reader.getLocalName());
        final String localName = element.getLocalName();
        switch (element) {
            case INET_ADDRESS: {
                requireSingleAttribute(reader, Attribute.VALUE.getLocalName());
                final String value = reader.getAttributeValue(0);
                requireNoContent(reader);
                // todo: validate IP address
                criteria.add(localName, value);
                break;
            }
            case LINK_LOCAL_ADDRESS:
            case LOOPBACK:
            case MULTICAST:
            case POINT_TO_POINT:
            case PUBLIC_ADDRESS:
            case SITE_LOCAL_ADDRESS:
            case UP:
            case VIRTUAL: {
                criteria.add(localName);
                break;
            }
            case NIC: {
                requireSingleAttribute(reader, Attribute.NAME.getLocalName());
                final String value = reader.getAttributeValue(0);
                requireNoContent(reader);
                // todo: validate NIC name
                criteria.add(localName, value);
                break;
            }
            case NIC_MATCH: {
                requireSingleAttribute(reader, Attribute.PATTERN.getLocalName());
                final String value = reader.getAttributeValue(0);
                requireNoContent(reader);
                // todo: validate pattern
                criteria.add(localName, value);
                break;
            }
            case SUBNET_MATCH: {
                requireSingleAttribute(reader, Attribute.VALUE.getLocalName());
                final String value = reader.getAttributeValue(0);
                requireNoContent(reader);

                final String[] split = value.split("/");
                try {
                    if (split.length != 2) {
                        throw new XMLStreamException("Invalid 'value' " + value + " -- must be of the form address/mask", reader.getLocation());
                    }
                    // todo - possible DNS hit here
                    final InetAddress addr = InetAddress.getByName(split[1]);
                    final byte[] net = addr.getAddress();
                    final int mask = Integer.parseInt(split[1]);
                    final ModelNode node = criteria.add().set(localName, new ModelNode()).get(localName);
                    node.get("network").set(net);
                    node.get("mask").set(mask);
                    break;
                }
                catch (final NumberFormatException e) {
                    throw new XMLStreamException("Invalid mask " + split[1] + " (" + e.getLocalizedMessage() +")", reader.getLocation(), e);
                }
                catch (final UnknownHostException e) {
                    throw new XMLStreamException("Invalid address " + split[1] + " (" + e.getLocalizedMessage() +")", reader.getLocation(), e);
                }
            }
            default: throw unexpectedElement(reader);
        }
    }

    protected void parseInterfaces(final XMLExtendedStreamReader reader, final Set<String> names, final ModelNode address, final List<ModelNode> list, final boolean checkSpecified) throws XMLStreamException {
        requireNoAttributes(reader);

        while (reader.nextTag() != END_ELEMENT) {
            // Attributes
            requireSingleAttribute(reader, Attribute.NAME.getLocalName());
            final String name = reader.getAttributeValue(0);
            if (! names.add(name)) {
                throw new XMLStreamException("Duplicate interface declaration", reader.getLocation());
            }
            final ModelNode interfaceAdd = new ModelNode();
            interfaceAdd.get("address").set(address);
            interfaceAdd.get("operation").set("add-interface");

            final ModelNode criteriaNode = interfaceAdd.get("criteria");
            parseInterfaceCriteria(reader, criteriaNode);

            if (criteriaNode.asInt() == 0 && checkSpecified) {
                throw unexpectedEndElement(reader);
            }
            list.add(interfaceAdd);
        }
    }

    protected void parseSocketBindingGroup(final XMLExtendedStreamReader reader, final Set<String> interfaces, final ModelNode address, final List<ModelNode> bindingUpdates, final boolean allowInclude) throws XMLStreamException {
        final Set<String> includedGroups = new HashSet<String>();
        final Set<String> socketBindings = new HashSet<String>();

        // Handle attributes
        final String[] attrValues = requireAttributes(reader, Attribute.NAME.getLocalName(), Attribute.DEFAULT_INTERFACE.getLocalName());
        final String name = attrValues[0];
        final String defaultInterface = attrValues[1];

        final ModelNode bindingGroupUpdate = new ModelNode();
        bindingGroupUpdate.get("address").set(address).add("socket-binding-group", name);
        final ModelNode bindings = bindingGroupUpdate.get("bindings");
        bindings.setEmptyList();

        // Handle elements
        while (reader.nextTag() != END_ELEMENT) {
            switch (Namespace.forUri(reader.getNamespaceURI())) {
                case DOMAIN_1_0: {
                    final Element element = Element.forName(reader.getLocalName());
                    switch (element) {
                        case INCLUDE: {
                            if(! allowInclude) {
                                // no include in standalone
                                throw unexpectedElement(reader);
                            }
                            final String includedGroup = readStringAttributeElement(reader, Attribute.SOCKET_BINDING_GROUP.getLocalName());
                            if (includedGroups.contains(includedGroup)) {
                                throw new XMLStreamException("Included socket-binding-group " + includedGroup + " already declared", reader.getLocation());
                            }
                            includedGroups.add(includedGroup);
                            break;
                        }
                        case SOCKET_BINDING: {
                            final String bindingName = parseSocketBinding(reader, interfaces, bindings.add(), defaultInterface);
                            if (socketBindings.contains(bindingName)) {
                                throw new XMLStreamException("socket-binding " + bindingName + " already declared", reader.getLocation());
                            }
                            socketBindings.add(bindingName);
                            break;
                        }
                        default:
                            throw unexpectedElement(reader);
                    }
                    break;
                }
                default:
                    throw unexpectedElement(reader);
            }
        }
        bindingUpdates.add(bindingGroupUpdate);
    }

    protected void parseSocketBindingGroupRef(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> updates) throws XMLStreamException {
        // Handle attributes
        String name = null;
        int offset = -1;
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw ParseUtils.unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                switch (attribute) {
                    case REF: {
                        if (name != null)
                            throw ParseUtils.duplicateAttribute(reader, attribute.getLocalName());
                        name = value;
                        break;
                    }
                    case PORT_OFFSET: {
                        try {
                            if (offset != -1)
                                throw ParseUtils.duplicateAttribute(reader, attribute.getLocalName());
                            offset = Integer.parseInt(value);
                            if (offset < 0) {
                                throw new XMLStreamException(offset + " is not a valid " +
                                        attribute.getLocalName() + " -- must be greater than zero",
                                        reader.getLocation());
                            }
                        } catch (final NumberFormatException e) {
                            throw new XMLStreamException(offset + " is not a valid " +
                                    attribute.getLocalName(), reader.getLocation(), e);
                        }
                        break;
                    }
                    default:
                        throw ParseUtils.unexpectedAttribute(reader, i);
                }
            }
        }
        if (name == null) {
            throw ParseUtils.missingRequired(reader, Collections.singleton(Attribute.REF));
        }

        // Handle elements
        ParseUtils.requireNoContent(reader);

        ModelNode update = new ModelNode();
        update.get("address").set(address);
        update.get("operation").set("set-socket-binding-group");
        update.get("name").set(name);
        updates.add(update);

        if (offset < 0) {
            offset = 0;
        }
        update = new ModelNode();
        update.get("address").set(address);
        update.get("operation").set("set-socket-binding-port-offset");
        update.get("offset").set(offset);
        updates.add(update);
    }

    protected String parseSocketBinding(final XMLExtendedStreamReader reader, final Set<String> interfaces, final ModelNode binding, final String inheritedInterfaceName) throws XMLStreamException {

        final EnumSet<Attribute> required = EnumSet.of(Attribute.NAME);
        String name = null;

        // Handle attributes
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i ++) {
            final String value = reader.getAttributeValue(i);
            if (reader.getAttributeNamespace(i) != null) {
                throw unexpectedAttribute(reader, i);
            } else {
                final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
                required.remove(attribute);
                switch (attribute) {
                    case NAME: {
                        binding.get("name").set(name = value);
                        break;
                    }
                    case INTERFACE: {
                        if (! interfaces.contains(value)) {
                            throw new XMLStreamException("Unknown interface " + value +
                                    " " + attribute.getLocalName() + " must be declared in element " +
                                    Element.INTERFACES.getLocalName(), reader.getLocation());
                        }
                        binding.get("interface").set(value);
                        break;
                    }
                    case PORT: {
                        binding.get("port").set(parseBoundedIntegerAttribute(reader, i, 0, 65535));
                        break;
                    }
                    case FIXED_PORT: {
                        binding.get("fixed-port").set(Boolean.parseBoolean(value));
                        break;
                    }
                    case MULTICAST_ADDRESS: {
                        try {
                            final InetAddress mcastAddr = InetAddress.getByName(value);
                            if (!mcastAddr.isMulticastAddress()) {
                                throw new XMLStreamException("Value " + value + " for attribute " +
                                        attribute.getLocalName() + " is not a valid multicast address",
                                        reader.getLocation());
                            }
                            binding.get("multicast-address").set(mcastAddr.toString());
                        } catch (final UnknownHostException e) {
                            throw new XMLStreamException("Value " + value + " for attribute " +
                                    attribute.getLocalName() + " is not a valid multicast address",
                                    reader.getLocation(), e);
                        }
                    }
                    case MULTICAST_PORT: {
                        binding.get("multicast-port").set(parseBoundedIntegerAttribute(reader, i, 1, 65535));
                        break;
                    }
                    default:
                        throw unexpectedAttribute(reader, i);
                }
            }
        }
        if (! binding.has("interface")) {
            binding.get("interface").set(inheritedInterfaceName);
        }
        if (! required.isEmpty()) {
            throw missingRequired(reader, required);
        }
        // Handle elements
        requireNoContent(reader);
        return name;
    }
}
