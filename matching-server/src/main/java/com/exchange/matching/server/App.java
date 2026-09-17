package com.exchange.matching.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {

	public static void main(String[] args) throws java.io.IOException {
		var options = java.util.Arrays.asList(args);
		var snapshot = options.stream().filter(arg -> arg.startsWith("--restore-snapshot=")).findFirst();
		var directory = options.stream().filter(arg -> arg.startsWith("--restore-directory=")).findFirst();
		if (snapshot.isPresent() || directory.isPresent()) {
			if (snapshot.isEmpty() || directory.isEmpty())
				throw new IllegalArgumentException("Both --restore-snapshot and --restore-directory are required");
			System.out.println(com.exchange.matching.server.service.SnapshotRecovery.restore(
					java.nio.file.Path.of(snapshot.get().substring("--restore-snapshot=".length())),
					java.nio.file.Path.of(directory.get().substring("--restore-directory=".length()))));
			return;
		}
        // ApplicationHome resolves the executable jar, independently of the shell working directory.
        var applicationHome = new org.springframework.boot.system.ApplicationHome(App.class);
        var source = applicationHome.getSource();
        var home = source != null && source.isFile() ? applicationHome.getDir() : new java.io.File(System.getProperty("user.dir"));
        System.setProperty("matching.home", home.getAbsolutePath());
        SpringApplication.run(App.class, args);
	}

}
