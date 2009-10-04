/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,  
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.webbeans.introspector.jlr;

import static org.jboss.webbeans.util.Reflections.ensureAccessible;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedParameter;

import org.jboss.webbeans.introspector.AnnotationStore;
import org.jboss.webbeans.introspector.MethodSignature;
import org.jboss.webbeans.introspector.WBClass;
import org.jboss.webbeans.introspector.WBMethod;
import org.jboss.webbeans.introspector.WBParameter;
import org.jboss.webbeans.resources.ClassTransformer;
import org.jboss.webbeans.util.Reflections;

import com.google.common.base.Supplier;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;

/**
 * Represents an annotated method
 * 
 * This class is immutable and therefore threadsafe
 * 
 * @author Pete Muir
 * 
 * @param <T>
 */
public class WBMethodImpl<T, X> extends AbstractWBCallable<T, X, Method> implements WBMethod<T, X>
{

   // The underlying method
   private final Method method;

   // The abstracted parameters
   private final List<WBParameter<?, ?>> parameters;
   // A mapping from annotation type to parameter abstraction with that
   // annotation present
   private final ListMultimap<Class<? extends Annotation>, WBParameter<?, ?>> annotatedParameters;

   // The property name
   private final String propertyName;

   // Cached string representation
   private final String toString;

   private final MethodSignature signature;

   public static <T, X> WBMethodImpl<T, X> of(Method method, WBClass<X> declaringClass, ClassTransformer classTransformer)
   {
      AnnotationStore annotationStore = AnnotationStore.of(method, classTransformer.getTypeStore());
      return new WBMethodImpl<T, X>(ensureAccessible(method), null, annotationStore, declaringClass, classTransformer);
   }
   
   public static <T, X> WBMethodImpl<T, X> of(AnnotatedMethod<T> method, WBClass<X> declaringClass, ClassTransformer classTransformer)
   {
      AnnotationStore annotationStore = AnnotationStore.of(method.getAnnotations(), method.getAnnotations(), classTransformer.getTypeStore());
      return new WBMethodImpl<T, X>(ensureAccessible(method.getJavaMember()), method, annotationStore, declaringClass, classTransformer);
   }

   /**
    * Constructor
    * 
    * Initializes the superclass with the built annotation map, sets the method
    * and declaring class abstraction and detects the actual type arguments
    * 
    * @param method The underlying method
    * @param declaringClass The declaring class abstraction
    */
   @SuppressWarnings("unchecked")
   private WBMethodImpl(Method method, AnnotatedMethod<T> annotatedMethod, AnnotationStore annotationStore, WBClass<X> declaringClass, ClassTransformer classTransformer)
   {
      super(annotationStore, method, (Class<T>) method.getReturnType(), method.getGenericReturnType(), declaringClass);
      this.method = method;
      this.toString = new StringBuilder().append("method ").append(method.toString()).toString();
      this.parameters = new ArrayList<WBParameter<?, ?>>();
      this.annotatedParameters = Multimaps.newListMultimap(new HashMap<Class<? extends Annotation>, Collection<WBParameter<?, ?>>>(), new Supplier< List<WBParameter<?, ?>>>()
      {
         
         public List<WBParameter<?, ?>> get()
         {
            return new ArrayList<WBParameter<?, ?>>();
         }
        
      });
      
      Map<Integer, AnnotatedParameter<?>> annotatedTypeParameters = new HashMap<Integer, AnnotatedParameter<?>>();
      
      if (annotatedMethod != null)
      {
         for (AnnotatedParameter<?> annotated : annotatedMethod.getParameters())
         {
            annotatedTypeParameters.put(annotated.getPosition(), annotated);
         }
      }

      for (int i = 0; i < method.getParameterTypes().length; i++)
      {
         if (method.getParameterAnnotations()[i].length > 0 || annotatedTypeParameters.containsKey(i))
         {
            Class<? extends Object> clazz = method.getParameterTypes()[i];
            Type type = method.getGenericParameterTypes()[i];
            WBParameter<?, ?> parameter = null;
            if (annotatedTypeParameters.containsKey(i))
            {
               AnnotatedParameter<?> annotatedParameter = annotatedTypeParameters.get(i);
               parameter = WBParameterImpl.of(annotatedParameter.getAnnotations(), clazz, type, this, i, classTransformer);            
            }
            else
            {
               parameter = WBParameterImpl.of(method.getParameterAnnotations()[i], clazz, type, this, i, classTransformer);
            }
            this.parameters.add(parameter);
            for (Annotation annotation : parameter.getAnnotations())
            {
               if (MAPPED_PARAMETER_ANNOTATIONS.contains(annotation.annotationType()))
               {
                  annotatedParameters.put(annotation.annotationType(), parameter);
               }
            }
         }
         else
         {
            Class<? extends Object> clazz = method.getParameterTypes()[i];
            Type type = method.getGenericParameterTypes()[i];
            WBParameter<?, ?> parameter = WBParameterImpl.of(new Annotation[0], (Class<Object>) clazz, type, this, i, classTransformer);
            this.parameters.add(parameter);
         }  
      }

      String propertyName = Reflections.getPropertyName(getDelegate());
      if (propertyName == null)
      {
         this.propertyName = getName();
      }
      else
      {
         this.propertyName = propertyName;
      }
      this.signature = new MethodSignatureImpl(this);
      
   }

   public Method getAnnotatedMethod()
   {
      return method;
   }

   @Override
   public Method getDelegate()
   {
      return method;
   }

   public List<WBParameter<?, ?>> getWBParameters()
   {
      return Collections.unmodifiableList(parameters);
   }

   public Class<?>[] getParameterTypesAsArray()
   {
      return method.getParameterTypes();
   }

   public List<WBParameter<?, ?>> getAnnotatedWBParameters(Class<? extends Annotation> annotationType)
   {
      return Collections.unmodifiableList(annotatedParameters.get(annotationType));
   }

   @Override
   public boolean equals(Object other)
   {
      if (other instanceof WBMethod)
      {
         WBMethod<?, ?> that = (WBMethod<?, ?>) other;
         return this.getDeclaringType().equals(that.getDeclaringType()) && this.getName().equals(that.getName()) && this.getWBParameters().equals(that.getWBParameters());
      }
      else
      {
         return false;
      }
   }

   public boolean isEquivalent(Method method)
   {
      return this.getDeclaringType().isEquivalent(method.getDeclaringClass()) && this.getName().equals(method.getName()) && Arrays.equals(this.getParameterTypesAsArray(), method.getParameterTypes());
   }

   @Override
   public int hashCode()
   {
      return getDelegate().hashCode();
   }

   public T invokeOnInstance(Object instance, Object...parameters) throws IllegalArgumentException, SecurityException, IllegalAccessException, InvocationTargetException, NoSuchMethodException
   {
      Method method = Reflections.lookupMethod(getName(), getParameterTypesAsArray(), instance);
      @SuppressWarnings("unchecked")
      T result = (T) method.invoke(instance, parameters);
      return result;
   }

   public T invoke(Object instance, Object... parameters) throws IllegalArgumentException, IllegalAccessException, InvocationTargetException
   {
      @SuppressWarnings("unchecked")
      T result = (T) method.invoke(instance, parameters);
      return result;
   }

   public String getPropertyName()
   {
      return propertyName;
   }

   @Override
   public String toString()
   {
      return this.toString;
   }

   public MethodSignature getSignature()
   {
      return signature;
   }

   public List<AnnotatedParameter<X>> getParameters()
   {
      return Collections.unmodifiableList((List) parameters);
   }

}
