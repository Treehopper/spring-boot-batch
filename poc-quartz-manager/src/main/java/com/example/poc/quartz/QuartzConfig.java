package com.example.poc.quartz;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Quartz Manager operates on a scheduler bean named {@value #QUARTZ_MANAGER_SCHEDULER}. By default it creates its
 * own scheduler with a RAM job store, so every schedule change made in the UI is lost on restart (its persistence
 * starter supports PostgreSQL only).
 * <p>
 * Instead, {@code quartz-manager.quartz.enabled=false} disables that scheduler, and Spring Boot's Quartz
 * auto-configuration creates a scheduler with a JDBC job store in the application's database. This alias makes it
 * the scheduler that Quartz Manager manages.
 */
@Configuration
public class QuartzConfig {

  public static final String QUARTZ_MANAGER_SCHEDULER = "quartzManagerScheduler";
  private static final String BOOT_SCHEDULER = "quartzScheduler";

  @Bean
  static BeanDefinitionRegistryPostProcessor quartzManagerSchedulerAlias() {
    return new BeanDefinitionRegistryPostProcessor() {
      @Override
      public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        registry.registerAlias(BOOT_SCHEDULER, QUARTZ_MANAGER_SCHEDULER);
      }

      @Override
      public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
      }
    };
  }
}
