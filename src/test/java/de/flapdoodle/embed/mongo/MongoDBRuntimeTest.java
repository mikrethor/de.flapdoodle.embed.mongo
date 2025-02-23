/**
 * Copyright (C) 2011
 *   Michael Mosmann <michael@mosmann.de>
 *   Martin Jöhren <m.joehren@googlemail.com>
 *
 * with contributions from
 * 	konstantin-ba@github,Archimedes Trajano	(trajano@github)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.flapdoodle.embed.mongo;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoClient;
import de.flapdoodle.embed.mongo.config.Defaults;
import de.flapdoodle.embed.mongo.config.MongodConfig;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.IFeatureAwareVersion;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.packageresolver.Command;
import de.flapdoodle.embed.mongo.packageresolver.Feature;
import de.flapdoodle.embed.process.config.ImmutableRuntimeConfig.Builder;
import de.flapdoodle.embed.process.config.RuntimeConfig;
import de.flapdoodle.embed.process.distribution.Distribution;
import de.flapdoodle.embed.process.extract.ExtractedFileSet;
import de.flapdoodle.embed.process.runtime.Network;
import de.flapdoodle.os.*;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static de.flapdoodle.embed.mongo.TestUtils.getCmdOptions;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MongoDBRuntimeTest {

	@Test
	public void testSingleVersion() throws IOException {
		Builder defaultBuilder = Defaults.runtimeConfigFor(Command.MongoD);

		RuntimeConfig config = defaultBuilder.build();

		check(config, distributionOf(Version.V2_6_0, OS.Windows, CommonArchitecture.X86_32));
	}

	private Distribution distributionOf(IFeatureAwareVersion version, OS os, BitSize bitsize) {
		return Distribution.of(version, ImmutablePlatform.builder()
						.operatingSystem(os)
						.architecture(bitsize == BitSize.B32 ? CommonArchitecture.X86_32 : CommonArchitecture.X86_64)
						.build());
	}

	private Distribution distributionOf(IFeatureAwareVersion version, OS os, Architecture architecture) {
		return Distribution.of(version, ImmutablePlatform.builder()
						.operatingSystem(os)
						.architecture(architecture)
						.build());
	}

	@Test
	public void testDistributions() throws IOException {
		Builder defaultBuilder = Defaults.runtimeConfigFor(Command.MongoD);
		
		RuntimeConfig config = defaultBuilder.build();

		for (OS os : OS.values()) {
			for (Version.Main version : Versions.testableVersions(Version.Main.class)) {
				for (BitSize bitsize : BitSize.values()) {
					// there is no osx 32bit version for v2.2.1
					// there is no solaris 32bit version
					if (!skipThisVersion(os, version, bitsize)) {
						check(config, distributionOf(version, os, bitsize));
					}
				}
			}
		}
		config = defaultBuilder.artifactStore(Defaults.extractedArtifactStoreFor(Command.MongoD)
						.withDownloadConfig(Defaults.downloadConfigFor(Command.MongoD)
										.build())).build();

		for (IFeatureAwareVersion version : Versions.testableVersions(Version.Main.class)) {
			// there is no windows 2008 version for 1.8.5 
			boolean skip = version.asInDownloadPath().equals(Version.V1_8_5.asInDownloadPath());
			if (!skip)
				check(config, distributionOf(version, OS.Windows, CommonArchitecture.X86_64));
		}
	}

	private boolean skipThisVersion(OS os, IFeatureAwareVersion version, BitSize bitsize) {
		if (version.enabled(Feature.ONLY_64BIT) && bitsize==BitSize.B32) {
			return true;
		}
		
		if ((os == OS.OS_X) && (bitsize == BitSize.B32)) {
			// there is no osx 32bit version for v2.2.1 and above, so we dont check
			return true;
		}
		if ((os == OS.Solaris)  && (bitsize == BitSize.B32) || version.enabled(Feature.NO_SOLARIS_SUPPORT)) {
			return true;
		}
		if (os == OS.FreeBSD) {
			return true;
		}
		return false;
	}

	private void check(RuntimeConfig runtime, Distribution distribution) throws IOException {
		assertTrue("Check", runtime.artifactStore().extractFileSet(distribution).isPresent());
		ExtractedFileSet files = runtime.artifactStore().extractFileSet(distribution).get();
		assertNotNull("Extracted", files);
		assertNotNull("Extracted", files.executable());
		assertTrue("Delete", files.executable().delete());
	}

	@Test
	public void testCheck() throws IOException {

		Timer timer = new Timer();

		int port = Network.getFreeServerPort();
		MongodProcess mongodProcess = null;
		MongodExecutable mongod = null;
		
		RuntimeConfig runtimeConfig = Defaults.runtimeConfigFor(Command.MongoD).build();
		MongodStarter runtime = MongodStarter.getInstance(runtimeConfig);

		timer.check("After Runtime");

		try {
			final Version.Main version = Version.Main.PRODUCTION;
			mongod = runtime.prepare(MongodConfig.builder()
					.version(version)
					.net(new Net(port, Network.localhostIsIPv6()))
					.cmdOptions(getCmdOptions(version))
					.build());
			timer.check("After mongod");
			assertNotNull("Mongod", mongod);
			mongodProcess = mongod.start();
			timer.check("After mongodProcess");

			try (MongoClient mongo = new MongoClient("localhost", port)) {
				timer.check("After Mongo");
				DB db = mongo.getDB("test");
				timer.check("After DB test");
				DBCollection col = db.createCollection("testCol", new BasicDBObject());
				timer.check("After Collection testCol");
				col.save(new BasicDBObject("testDoc", new Date()));
				timer.check("After save");
			}

		} finally {
			if (mongodProcess != null)
				mongodProcess.stop();
			timer.check("After mongodProcess stop");
			if (mongod != null)
				mongod.stop();
			timer.check("After mongod stop");
		}
		timer.log();
	}

	static class Timer {

		long _start = System.currentTimeMillis();
		long _last = _start;

		List<String> _log = new ArrayList<>();

		void check(String label) {
			long current = System.currentTimeMillis();
			long diff = current - _last;
			_last = current;

			_log.add(label + ": " + diff + "ms");
		}

		void log() {
			for (String line : _log) {
				System.out.println(line);
			}
		}
	}

}
