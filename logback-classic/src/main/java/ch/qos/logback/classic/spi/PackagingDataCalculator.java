/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2009, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package ch.qos.logback.classic.spi;

import java.lang.reflect.Method;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;

import sun.reflect.Reflection;

/**
 * 
 * Given a classname locate associated PackageInfo (jar name, version name).
 * 
 * @author James Strachan
 * @Ceki G&uuml;lc&uuml;
 */
public class PackagingDataCalculator {

  final static StackTraceElementProxy[] STEP_ARRAY_TEMPLATE = new StackTraceElementProxy[0];

  HashMap<String, ClassPackagingData> cache = new HashMap<String, ClassPackagingData>();

  private static boolean GET_CALLER_CLASS_METHOD_AVAILABLE = false;

  static {
    // if either the Reflection class or the getCallerClass method
    // are unavailable, then we won't invoke Reflection.getCallerClass()
    // This approach ensures that this class will *run* on JDK's lacking
    // sun.reflect.Reflection class. However, this class will *not compile*
    // on JDKs lacking sun.reflect.Reflection.
    try {
      Reflection.getCallerClass(2);
      GET_CALLER_CLASS_METHOD_AVAILABLE = true;
    } catch (NoClassDefFoundError e) {
    } catch (NoSuchMethodError e) {
    } catch (Throwable e) {
      System.err.println("Unexpected exception");
      e.printStackTrace();
    }
  }

  public PackagingDataCalculator() {
  }

  public void calculate(final IThrowableProxy tp) {
    if (System.getSecurityManager() != null) {
      AccessController.doPrivileged(new PrivilegedAction<Void>() {
        public Void run() {
          doCalculate(tp);
          return null;
        }
      });
    }
    else {
      doCalculate(tp);
    }
  }
  
  private void doCalculate(IThrowableProxy tp) {
    while (tp != null) {
      populateFrames(tp.getStackTraceElementProxyArray());
      tp = tp.getCause();
    }
  }

  void populateFrames(StackTraceElementProxy[] stepArray) {
    // in the initial part of this method we populate package information for
    // common stack frames
    final Throwable t = new Throwable("local stack reference");
    final StackTraceElement[] localteSTEArray = t.getStackTrace();
    final int commonFrames = STEUtil.findNumberOfCommonFrames(localteSTEArray,
        stepArray);
    final int localFirstCommon = localteSTEArray.length - commonFrames;
    final int stepFirstCommon = stepArray.length - commonFrames;

    ClassLoader lastExactClassLoader = null;
    ClassLoader firsExactClassLoader = null;

    int missfireCount = 0;
    for (int i = 0; i < commonFrames; i++) {
      Class callerClass = null;
      if (GET_CALLER_CLASS_METHOD_AVAILABLE) {
        callerClass = Reflection.getCallerClass(localFirstCommon + i
            - missfireCount + 1);
      }
      StackTraceElementProxy step = stepArray[stepFirstCommon + i];
      String stepClassname = step.ste.getClassName();

      if (callerClass != null && stepClassname.equals(callerClass.getName())) {
        lastExactClassLoader = callerClass.getClassLoader();
        if (firsExactClassLoader == null) {
          firsExactClassLoader = callerClass.getClassLoader();
        }
        ClassPackagingData pi = calculateByExactType(callerClass);
        step.setClassPackagingData(pi);
      } else {
        missfireCount++;
        ClassPackagingData pi = computeBySTEP(step, lastExactClassLoader);
        step.setClassPackagingData(pi);
      }
    }
    populateUncommonFrames(commonFrames, stepArray, firsExactClassLoader);
  }

  void populateUncommonFrames(int commonFrames,
      StackTraceElementProxy[] stepArray, ClassLoader firstExactClassLoader) {
    int uncommonFrames = stepArray.length - commonFrames;
    for (int i = 0; i < uncommonFrames; i++) {
      StackTraceElementProxy step = stepArray[i];
      ClassPackagingData pi = computeBySTEP(step, firstExactClassLoader);
      step.setClassPackagingData(pi);
    }
  }

  private ClassPackagingData calculateByExactType(Class type) {
    String className = type.getName();
    ClassPackagingData cpd = cache.get(className);
    if (cpd != null) {
      return cpd;
    }
    String version = getImplementationVersion(type);
    String codeLocation = getCodeLocation(type);
    cpd = new ClassPackagingData(codeLocation, version);
    cache.put(className, cpd);
    return cpd;
  }

  private ClassPackagingData computeBySTEP(StackTraceElementProxy step,
      ClassLoader lastExactClassLoader) {
    String className = step.ste.getClassName();
    ClassPackagingData cpd = cache.get(className);
    if (cpd != null) {
      return cpd;
    }
    Class type = bestEffortLoadClass(lastExactClassLoader, className);
    String version = getImplementationVersion(type);
    String codeLocation = getCodeLocation(type);
    cpd = new ClassPackagingData(codeLocation, version, false);
    cache.put(className, cpd);
    return cpd;
  }

  String getImplementationVersion(Class type) {
    if (type == null) {
      return "na";
    }
    Object b = getBundle(type);
    if (b != null) {
      return getBundleVersion(b);
    }
    Package aPackage = type.getPackage();
    if (aPackage != null) {
      String v = aPackage.getImplementationVersion();
      if (v == null) {
        return "na";
      } else {
        return v;
      }
    }
    return "na";

  }

  String getCodeLocation(Class type) {
    try {
      if (type != null) {
        Object b = getBundle(type);
	if (b != null) {
	  return getBundleName(b);
	}
        // file:/C:/java/maven-2.0.8/repo/com/icegreen/greenmail/1.3/greenmail-1.3.jar
        URL resource = type.getProtectionDomain().getCodeSource().getLocation();
        if (resource != null) {
          String locationStr = resource.toString();
          // now lets remove all but the file name
          String result = getCodeLocation(locationStr, '/');
          if (result != null) {
            return result;
          }
          return getCodeLocation(locationStr, '\\');
        }
      }
    } catch (Exception e) {
      // ignore
    }
    return "na";
  }

  private String getCodeLocation(String locationStr, char separator) {
    int idx = locationStr.lastIndexOf(separator);
    if (isFolder(idx, locationStr)) {
      idx = locationStr.lastIndexOf(separator, idx - 1);
      return locationStr.substring(idx + 1);
    } else if (idx > 0) {
      return locationStr.substring(idx + 1);
    }
    return null;
  }

  private boolean isFolder(int idx, String text) {
    return (idx != -1 && idx + 1 == text.length());
  }

  private Class loadClass(ClassLoader cl, String className) {
    if (cl == null) {
      return null;
    }
    try {
      return cl.loadClass(className);
    } catch (ClassNotFoundException e1) {
      return null;
    } catch (NoClassDefFoundError e1) {
      return null;
    } catch (IllegalStateException e1) {
      // happens in osgi environments
      return null;
    } catch (Exception e) {
      e.printStackTrace(); // this is unexpected
      return null;
    }

  }

  /**
   * 
   * @param lastGuaranteedClassLoader
   *                may be null
   * 
   * @param className
   * @return
   */
  private Class bestEffortLoadClass(ClassLoader lastGuaranteedClassLoader,
      String className) {
    Class result = loadClass(lastGuaranteedClassLoader, className);
    if (result != null) {
      return result;
    }
    ClassLoader tccl = Thread.currentThread().getContextClassLoader();
    if (tccl != lastGuaranteedClassLoader) {
      result = loadClass(tccl, className);
    }
    if (result != null) {
      return result;
    }

    try {
      return Class.forName(className);
    } catch (ClassNotFoundException e1) {
      return null;
    } catch (NoClassDefFoundError e1) {
      return null;
    } catch (Exception e) {
      e.printStackTrace(); // this is unexpected
      return null;
    }
  }

  // call OSGi methods reflectively,
  // as we may not be running in OSGi framework

  private static boolean setBundle = false;
  private static Method bundleMethod;

  private Object getBundle(Class type) {
    if (bundleMethod == null) {
      if (!setBundle) {
	try {
	  Class c = getClass().getClassLoader().loadClass("org.osgi.framework.FrameworkUtil");
	  bundleMethod = c.getMethod("getBundle", new Class[] { Class.class});
	}
	catch (Exception e) {
	}
      }

      setBundle = true;
      if (bundleMethod == null) {
        return null;
      }
    }

    try {
      return bundleMethod.invoke(null, new Object[] { type });
    }
    catch (Exception e) {
    }
    return null;
  }

  private String getBundleName(Object bundle) {
    try {
      Method m = bundle.getClass().getMethod("getSymbolicName", null);
      return (String) m.invoke(bundle, null);
    }
    catch (Exception e) {
    }
    return "na";
  }

  private String getBundleVersion(Object bundle) {
    try {
      Method m = bundle.getClass().getMethod("getVersion", null);
      return m.invoke(bundle, null).toString();
    }
    catch (Exception e) {
    }
    return "na";
  }

}
