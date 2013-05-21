/* 
 * JBoss, Home of Professional Open Source
 * Copyright 2013 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
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
package org.jboss.qa.jdg.config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.infinispan.api.BasicCacheContainer;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.infinispan.util.FileLookupFactory;
import org.jgroups.JChannel;
import org.jgroups.annotations.Property;
import org.jgroups.stack.Protocol;

/**
 * 
 * Config normalizer. Outputs effective infinispan configuration into a flat properties-like
 * structure.
 * 
 * @author Michal Linhard (mlinhard@redhat.com)
 */
public class ConfigNormalizer {

   private static Method plainToString = null;
   static {
      try {
         plainToString = Object.class.getMethod("toString");
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   /**
    * 
    * Returns properties made by reflection of
    * 
    * @param globalConfiguration
    *           global configuration
    * @param cacheConfigurations
    *           map cacheName -> cacheConfig
    * @param jgroupsChannel
    *           JGroups channel
    * @return configuration in form of properties
    * @throws Exception
    */
   public static Properties reflectProperties(GlobalConfiguration globalConfiguration, Map<String, Configuration> cacheConfigurations, JChannel jgroupsChannel)
         throws Exception {
      Properties p = new Properties();
      reflect(globalConfiguration, p, "global");
      for (Entry<String, Configuration> ent : cacheConfigurations.entrySet()) {
         reflect(ent.getValue(), p, "cache." + ent.getKey());
      }
      if (jgroupsChannel != null) {
         getJGroupsConfig("jgroups", p, jgroupsChannel);
      }
      return p;
   }

   /**
    * 
    * Stores the properties in sorted order into a regular properties file.
    * 
    * @param properties
    * @param file
    * @throws Exception
    */
   public static void storeSortedPropertiesAsXML(Properties properties, String file) throws Exception {
      Properties props = new Properties() {
         @Override
         public Set<Object> keySet() {
            return Collections.unmodifiableSet(new TreeSet<Object>(super.keySet()));
         }

         @Override
         public synchronized Enumeration<Object> keys() {
            return Collections.enumeration(new TreeSet<Object>(super.keySet()));
         }
      };
      props.putAll(properties);
      props.storeToXML(new FileOutputStream(file), null, "UTF-8");
   }

   /**
    * 
    * Stores the properties in sorted order into a XML file.
    * 
    * @param properties
    * @param file
    * @throws Exception
    */
   public static void storeSortedProperties(Properties properties, String file) throws Exception {
      Properties props = new Properties() {
         @Override
         public Set<Object> keySet() {
            return Collections.unmodifiableSet(new TreeSet<Object>(super.keySet()));
         }

         @Override
         public synchronized Enumeration<Object> keys() {
            return Collections.enumeration(new TreeSet<Object>(super.keySet()));
         }
      };
      props.putAll(properties);
      props.store(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"), null);
   }

   private static void findJars(List<URL> jarPaths, File dir) throws MalformedURLException {
      for (File f : dir.listFiles()) {
         if (f.isFile()) {
            if (f.getName().endsWith(".jar")) {
               jarPaths.add(f.toURI().toURL());
            }
         } else if (f.isDirectory()) {
            findJars(jarPaths, f);
         }
      }
   }

   private static ClassLoader getConfigClassLoader() throws MalformedURLException {
      String addClasspathDir = System.getProperty("config.normalizer.add.classpath");
      if (addClasspathDir != null) {
         ClassLoader parent = Thread.currentThread().getContextClassLoader();
         List<URL> urls = new ArrayList<URL>();
         findJars(urls, new File(addClasspathDir));
         return new URLClassLoader(urls.toArray(new URL[urls.size()]), parent);
      } else {
         return Thread.currentThread().getContextClassLoader();
      }
   }

   private static class FakeJGroupsTransport extends JGroupsTransport {
      @Override
      public void initChannel() {
         super.initChannel();
      }
   }

   public static void main(String[] args) throws Exception {
      ClassLoader configClassLoader = getConfigClassLoader();
      ConfigurationBuilderHolder holder = new ParserRegistry(configClassLoader).parse(FileLookupFactory.newInstance().lookupFileStrict(args[0],
            configClassLoader));

      Map<String, Configuration> cacheConfigurations = new HashMap<String, Configuration>();
      GlobalConfiguration globalConfiguration = holder.getGlobalConfigurationBuilder().build();
      cacheConfigurations.put(BasicCacheContainer.DEFAULT_CACHE_NAME, holder.getDefaultConfigurationBuilder().build());
      for (String cacheName : holder.getNamedConfigurationBuilders().keySet()) {
         cacheConfigurations.put(cacheName, holder.getNamedConfigurationBuilders().get(cacheName).build());
      }
      Properties p = reflectProperties(globalConfiguration, cacheConfigurations, getInitializedJChannel(globalConfiguration));
      storeSortedPropertiesAsXML(p, args[1]);
   }

   private static JChannel getInitializedJChannel(GlobalConfiguration globalConfiguration) {
      FakeJGroupsTransport fTransport = new FakeJGroupsTransport();
      fTransport.setConfiguration(globalConfiguration);
      fTransport.initChannel();
      return (JChannel) fTransport.getChannel();
   }

   private static void getJGroupsConfig(String prefix, Properties p, JChannel jChannel) throws Exception {
      for (Protocol proto : jChannel.getProtocolStack().getProtocols()) {
         reflectJGroupsProtocol(prefix, p, proto);
      }
   }

   private static void reflectJGroupsProtocol(String prefix, Properties p, Protocol proto) throws Exception {
      for (Field field : proto.getClass().getDeclaredFields()) {
         if (field.isAnnotationPresent(Property.class)) {
            field.setAccessible(true);
            Object val = field.get(proto);
            p.put(prefix + "." + proto.getName() + "." + field.getName(), val == null ? "null" : val.toString());
         }
      }
   }

   private static boolean hasPlainToString(Class<?> cls, Object obj) {
      try {
         if (cls.getMethod("toString") == plainToString) {
            return true;
         }
         String plainToStringValue = cls.getName() + "@" + Integer.toHexString(obj.hashCode());
         return plainToStringValue.equals(obj.toString());
      } catch (Exception e) {
         return false;
      }
   }

   private static void reflect(Object obj, Properties p, String prefix) throws Exception {
      if (obj == null) {
         p.put(prefix, "null");
         return;
      }
      Class<?> cls = obj.getClass();
      if (cls.getName().startsWith("org.infinispan.config") && !cls.isEnum()) {
         for (Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0 || "toString".equals(m.getName()) || "hashCode".equals(m.getName())) {
               continue;
            }
            try {
               reflect(m.invoke(obj), p, prefix + "." + m.getName());
            } catch (IllegalAccessException e) {
               // ok
            }
         }
      } else if (Collection.class.isAssignableFrom(cls)) {
         Collection<?> collection = (Collection<?>) obj;
         Iterator<?> iter = collection.iterator();
         for (int i = 0; i < collection.size(); i++) {
            reflect(iter.next(), p, prefix + "[" + i + "]");
         }
      } else if (cls.isArray()) {
         Object[] a = (Object[]) obj;
         for (int i = 0; i < a.length; i++) {
            reflect(a[i], p, prefix + "[" + i + "]");
         }
      } else if (hasPlainToString(cls, obj)) {
         // we have a class that doesn't have a nice toString implementation
         p.put(prefix, cls.getName());
      } else {
         // we have a single value
         p.put(prefix, obj.toString());
      }
   }
}
