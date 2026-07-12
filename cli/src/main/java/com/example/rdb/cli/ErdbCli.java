package com.example.rdb.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "erdb-cli",
        mixinStandardHelpOptions = true,
        version = "erdb-cli 0.2.0",
        description = "Example RDB command-line tool",
        subcommands = {ImportCommand.class}
)
public class ErdbCli implements Runnable {

    @Override
    public void run() {
        System.out.println("Usage: erdb-cli import [options]");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ErdbCli()).execute(args);
        System.exit(exitCode);
    }
}
