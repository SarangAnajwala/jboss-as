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

package org.jboss.as.testsuite.integration.classloader.sealed;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.testsuite.integration.classloader.sealed.packageone.ClassInSealedPackage;
import org.jboss.as.testsuite.integration.classloader.sealed.packagetwo.ClassInUnsealedPackage;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.ejb.EJB;
import java.io.File;


/**
 * User: jpai
 */
@RunWith(Arquillian.class)
public class SealedPackageClassLoadingTestCase {

    @EJB(mappedName = "java:module/ClassLoadingEJB")
    private ClassLoadingEJB ejb;

    @Deployment
    public static Archive<?> createDeployment() {
//        final WebArchive war = ShrinkWrap.create(WebArchive.class, "sealed-test.war");
//        war.addClasses(SealedPackageClassLoadingTestCase.class, ClassLoadingEJB.class);

        final JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "sealed.jar");
        jar.addClasses(SimpleClass.class, ClassLoadingEJB.class);
        jar.addPackage(ClassInSealedPackage.class.getPackage());
        jar.addPackage(ClassInUnsealedPackage.class.getPackage());
        jar.addAsManifestResource("sealed/META-INF/MANIFEST.MF", "MANIFEST.MF");

        return jar;
    }

    @Test
    public void testSealedPackageLoad() throws Exception {
        final String classInSealedPackage = "org.jboss.as.testsuite.integration.classloader.sealed.packageone.ClassInSealedPackage";
        try {
            ejb.loadClass(classInSealedPackage);
            Assert.fail("Class loading was expected to fail for sealed class: " + classInSealedPackage);
        } catch (SecurityException se) {
            // expected
        }
    }
}
