/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
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
package org.jboss.as.cli.handlers.batch;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandLineException;
import org.jboss.as.cli.Util;
import org.jboss.as.cli.batch.Batch;
import org.jboss.as.cli.batch.BatchManager;
import org.jboss.as.cli.batch.BatchedCommand;
import org.jboss.as.cli.handlers.BaseOperationCommand;
import org.jboss.as.cli.handlers.DefaultFilenameTabCompleter;
import org.jboss.as.cli.handlers.FilenameTabCompleter;
import org.jboss.as.cli.handlers.WindowsFilenameTabCompleter;
import org.jboss.as.cli.impl.ArgumentWithValue;
import org.jboss.as.cli.impl.ArgumentWithoutValue;
import org.jboss.as.cli.impl.FileSystemPathArgument;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Alexey Loubyansky
 */
public class BatchRunHandler extends BaseOperationCommand {

    private final ArgumentWithValue file;
    private final ArgumentWithoutValue verbose;

    public BatchRunHandler(CommandContext ctx) {
        super(ctx, "batch-run", true);

        final FilenameTabCompleter pathCompleter = Util.isWindows() ? new WindowsFilenameTabCompleter(ctx) : new DefaultFilenameTabCompleter(ctx);
        file = new FileSystemPathArgument(this, pathCompleter, "--file");

        verbose = new ArgumentWithoutValue(this, "--verbose", "-v");
    }

    /* (non-Javadoc)
     * @see org.jboss.as.cli.handlers.CommandHandlerWithHelp#doHandle(org.jboss.as.cli.CommandContext)
     */
    @Override
    protected void doHandle(CommandContext ctx) throws CommandLineException {

        final boolean v = verbose.isPresent(ctx.getParsedCommandLine());

        final ModelNode response;
        boolean failed = false;
        try {
            final ModelNode request = buildRequest(ctx);

            if (ctx.getConfig().isValidateOperationRequests() && request.get(Util.OPERATION).asString().equals(Util.COMPOSITE)
                    && request.hasDefined(Util.STEPS)) {
                final List<ModelNode> steps = request.get(Util.STEPS).asList();
                for (ModelNode step : steps) {
                    ModelNode opDescOutcome = Util.validateRequest(ctx, step);
                    if (opDescOutcome != null) { // operation has params that might need to be replaced
                        Util.replaceFilePathsWithBytes(request, opDescOutcome);
                    }
                }
            }

            final ModelControllerClient client = ctx.getModelControllerClient();
            try {
                response = client.execute(request);
            } catch(Exception e) {
                throw new CommandFormatException("Failed to perform operation: " + e.getLocalizedMessage());
            }
            if (!Util.isSuccess(response)) {
                throw new CommandFormatException(Util.getFailureDescription(response));
            }
        } catch(CommandLineException e) {
            failed = true;
            throw new CommandLineException("The batch failed with the following error "
                    + "(you are remaining in the batch editing mode to have a chance to correct the error)", e);
        } finally{
            if(!failed) {
                if(ctx.getBatchManager().isBatchActive()) {
                    ctx.getBatchManager().discardActiveBatch();
                }
            }
        }

        if(v) {
            ctx.printLine(response.toString());
        } else {
            ctx.printLine("The batch executed successfully");
            super.handleResponse(ctx, response, true);
        }
    }

    @Override
    protected ModelNode buildRequestWOValidation(CommandContext ctx) throws CommandFormatException {

        final String path = file.getValue(ctx.getParsedCommandLine());
        final ModelNode headersNode = headers.isPresent(ctx.getParsedCommandLine()) ? headers.toModelNode(ctx) : null;

        final BatchManager batchManager = ctx.getBatchManager();
        if(batchManager.isBatchActive()) {
            if(path != null) {
                throw new CommandFormatException("--file is not allowed in the batch mode.");
            }
            final Batch batch = batchManager.getActiveBatch();
            List<BatchedCommand> currentBatch = batch.getCommands();
            if(currentBatch.isEmpty()) {
                batchManager.discardActiveBatch();
                throw new CommandFormatException("The batch is empty.");
            }
            final ModelNode request = batch.toRequest();
            if(headersNode != null) {
                request.get(Util.OPERATION_HEADERS).set(headersNode);
            }
            return request;
        }

        if(path != null) {
            final File f = new File(path);
            if(!f.exists()) {
                throw new CommandFormatException("File " + f.getAbsolutePath() + " does not exist.");
            }

            final File currentDir = ctx.getCurrentDir();
            final File baseDir = f.getParentFile();
            if(baseDir != null) {
                ctx.setCurrentDir(baseDir);
            }

            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(f));
                String line = reader.readLine();
                batchManager.activateNewBatch();
                while(line != null) {
                    ctx.handle(line);
                    line = reader.readLine();
                }
                final ModelNode request = batchManager.getActiveBatch().toRequest();
                if(headersNode != null) {
                    request.get(Util.OPERATION_HEADERS).set(headersNode);
                }
                return request;
            } catch(IOException e) {
                throw new CommandFormatException("Failed to read file " + f.getAbsolutePath(), e);
            } catch(CommandLineException e) {
                throw new CommandFormatException("Failed to create batch from " + f.getAbsolutePath(), e);
            } finally {
                batchManager.discardActiveBatch();
                if(baseDir != null) {
                    ctx.setCurrentDir(currentDir);
                }
                if(reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {}
                }
            }
        }

        throw new CommandFormatException("Without arguments the command can be executed only in the batch mode.");
    }

    @Override
    protected void handleResponse(CommandContext ctx, ModelNode response, boolean composite) throws CommandLineException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected ModelNode buildRequestWithoutHeaders(CommandContext ctx) throws CommandFormatException {
        throw new UnsupportedOperationException();
    }
}
