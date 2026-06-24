/*
 * Copyright (c) 2026 OmniFish and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 */
package ee.omnifish.arquillian.container.glassfish;

import java.util.List;

/**
 * Executes a single GlassFish admin command against a running DAS. Each
 * container module supplies its own implementation — managed via an asadmin
 * subprocess, embedded via the in-process {@code CommandRunner}, pool via
 * asadmin targeting the leased slot's admin port — so {@link PostBootCommands}
 * can own the shared parse-and-loop without knowing how a command is run.
 */
@FunctionalInterface
public interface AdminCommandExecutor {

    /**
     * The first token is not necessarily the subcommand: a post-boot line may
     * lead with an asadmin utility option such as {@code --passwordfile <file>}.
     * Implementations therefore append {@code command} and {@code arguments}
     * verbatim, in order, after asadmin's own options — asadmin accepts utility
     * options in any order ahead of the subcommand.
     *
     * @param command the first token of the command line
     * @param arguments the remaining tokens, in order
     * @throws Exception if the command fails; {@link PostBootCommands} decides
     *         whether to abort or warn-and-continue
     */
    void run(String command, List<String> arguments) throws Exception;
}
