/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.jboss.as.server.services.net;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEFAULT_INTERFACE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PORT_OFFSET;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING_GROUP;

import java.util.Set;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.operations.common.AbstractSocketBindingGroupAddHandler;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.registry.Resource.ResourceEntry;
import org.jboss.as.controller.resource.AbstractSocketBindingGroupResourceDefinition;
import org.jboss.as.network.NetworkInterfaceBinding;
import org.jboss.as.network.SocketBindingManager;
import org.jboss.as.server.logging.ServerLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceController;

/**
 * Handler for the server socket-binding-group resource's add operation.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class BindingGroupAddHandler extends AbstractSocketBindingGroupAddHandler {

    public static ModelNode getOperation(PathAddress address, ModelNode model) {
        ModelNode op = Util.createAddOperation(address);
        op.get(DEFAULT_INTERFACE).set(model.get(DEFAULT_INTERFACE));
        op.get(PORT_OFFSET).set(model.get(PORT_OFFSET));
        return op;
    }

    public static final BindingGroupAddHandler INSTANCE = new BindingGroupAddHandler();

    private BindingGroupAddHandler() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void populateModel(final OperationContext context, final ModelNode operation, final Resource resource) throws OperationFailedException {

        super.populateModel(context, operation, resource);
        final ModelNode model = resource.getModel();

        SocketBindingGroupResourceDefinition.PORT_OFFSET.validateAndSet(operation, model);

        // Validate only a single socket binding group and a valid default interface
        final PathAddress mine = PathAddress.pathAddress(operation.require(OP_ADDR));
        context.addStep(new OperationStepHandler() {
            @Override
            public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

                // Do a non-recursive read, which will bring in placeholders for the children
                final Resource root = context.readResourceFromRoot(context.getCurrentAddress().getParent(), false);

                Set<ResourceEntry> children = root.getChildren(SOCKET_BINDING_GROUP);
                if (children.size() > 1) {
                    for (ResourceEntry entry : children) {
                        if (!entry.getName().equals(mine.getLastElement().getValue())) {
                            throw ServerLogger.ROOT_LOGGER.cannotAddMoreThanOneSocketBindingGroupForServerOrHost(
                                    mine,
                                    PathAddress.pathAddress(PathElement.pathElement(SOCKET_BINDING_GROUP, entry.getName())));
                        }
                    }
                }

                AbstractSocketBindingGroupResourceDefinition.validateDefaultInterfaceReference(context, model);
            }
        }, OperationContext.Stage.MODEL);
    }

    @Override
    protected boolean requiresRuntime(OperationContext context) {
        return true;
    }

    @Override
    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {
        int portOffset = SocketBindingGroupResourceDefinition.PORT_OFFSET.resolveModelAttribute(context, model).asInt();
        String defaultInterface = SocketBindingGroupResourceDefinition.DEFAULT_INTERFACE.resolveModelAttribute(context, model).asString();

        SocketBindingManagerService service = new SocketBindingManagerService(portOffset);
        context.getServiceTarget().addService(SocketBindingManager.SOCKET_BINDING_MANAGER, service)
                .setInitialMode(ServiceController.Mode.ON_DEMAND)
                .addDependency(NetworkInterfaceService.JBOSS_NETWORK_INTERFACE.append(defaultInterface), NetworkInterfaceBinding.class, service.getDefaultInterfaceBindingInjector())
                .install();
    }
}
