/*
 * Copyright 2002-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.amqp.rabbit.listener;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.SingleConnectionFactory;
import org.springframework.amqp.rabbit.core.ChannelAwareMessageListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.transaction.RabbitTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;

/**
 * @author Gary Russell
 * @since 1.1.2
 *
 */
public class ExternalTxManagerTests {

	/**
	 * Verifies that an up-stack RabbitTemplate uses the listener's
	 * channel (MessageListener).
	 */
	@Test
	public void testMessageListener() throws Exception {
		ConnectionFactory mockConnectionFactory = mock(ConnectionFactory.class);
		Connection mockConnection = mock(Connection.class);
		final Channel onlyChannel = mock(Channel.class);
		when(onlyChannel.isOpen()).thenReturn(true);

		final CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory(mockConnectionFactory);

		when(mockConnectionFactory.newConnection((ExecutorService) null)).thenReturn(mockConnection);
		when(mockConnection.isOpen()).thenReturn(true);

		final AtomicReference<Exception> tooManyChannels = new AtomicReference<Exception>();

		doAnswer(new Answer<Channel>(){
			boolean done;
			@Override
			public Channel answer(InvocationOnMock invocation) throws Throwable {
				if (!done) {
					done = true;
					return onlyChannel;
				}
				tooManyChannels.set(new Exception("More than one channel requested"));
				Channel channel = mock(Channel.class);
				when(channel.isOpen()).thenReturn(true);
				return channel;
			}
		}).when(mockConnection).createChannel();

		final AtomicReference<Consumer> consumer = new AtomicReference<Consumer>();

		doAnswer(new Answer<String>() {

			@Override
			public String answer(InvocationOnMock invocation) throws Throwable {
				consumer.set((Consumer) invocation.getArguments()[2]);
				return null;
			}
		}).when(onlyChannel)
			.basicConsume(Mockito.anyString(), Mockito.anyBoolean(), Mockito.any(Consumer.class));

		final CountDownLatch commitLatch = new CountDownLatch(1);
		doAnswer(new Answer<String>() {

			@Override
			public String answer(InvocationOnMock invocation) throws Throwable {
				commitLatch.countDown();
				return null;
			}
		}).when(onlyChannel).txCommit();

		final CountDownLatch latch = new CountDownLatch(1);
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(cachingConnectionFactory);
		container.setMessageListener(new MessageListener() {
			public void onMessage(Message message) {
				RabbitTemplate rabbitTemplate = new RabbitTemplate(cachingConnectionFactory);
				rabbitTemplate.setChannelTransacted(true);
				// should use same channel as container
				rabbitTemplate.convertAndSend("foo", "bar", "baz");
				latch.countDown();
			}
		});
		container.setQueueNames("queue");
		container.setChannelTransacted(true);
		container.setShutdownTimeout(100);
		container.setTransactionManager(new DummyTxManager());
		container.afterPropertiesSet();
		container.start();

		consumer.get().handleDelivery("qux", new Envelope(1, false, "foo", "bar"), new BasicProperties(), new byte[] {0});

		assertTrue(latch.await(10, TimeUnit.SECONDS));

		Exception e = tooManyChannels.get();
		if (e != null) {
			throw e;
		}

		verify(mockConnection, Mockito.times(1)).createChannel();
		assertTrue(commitLatch.await(10, TimeUnit.SECONDS));
		verify(onlyChannel).txCommit();
		verify(onlyChannel).basicPublish(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(),
				Mockito.anyBoolean(), Mockito.any(BasicProperties.class), Mockito.any(byte[].class));
		container.stop();

	}

	/**
	 * Verifies that an up-stack RabbitTemplate uses the listener's
	 * channel (ChannelAwareMessageListener).
	 */
	@Test
	public void testChannelAwareMessageListener() throws Exception {
		ConnectionFactory mockConnectionFactory = mock(ConnectionFactory.class);
		Connection mockConnection = mock(Connection.class);
		final Channel onlyChannel = mock(Channel.class);
		when(onlyChannel.isOpen()).thenReturn(true);

		final SingleConnectionFactory cachingConnectionFactory = new SingleConnectionFactory(mockConnectionFactory);

		when(mockConnectionFactory.newConnection((ExecutorService) null)).thenReturn(mockConnection);
		when(mockConnection.isOpen()).thenReturn(true);

		final AtomicReference<Exception> tooManyChannels = new AtomicReference<Exception>();

		doAnswer(new Answer<Channel>(){
			boolean done;
			@Override
			public Channel answer(InvocationOnMock invocation) throws Throwable {
				if (!done) {
					done = true;
					return onlyChannel;
				}
				tooManyChannels.set(new Exception("More than one channel requested"));
				Channel channel = mock(Channel.class);
				when(channel.isOpen()).thenReturn(true);
				return channel;
			}
		}).when(mockConnection).createChannel();

		final AtomicReference<Consumer> consumer = new AtomicReference<Consumer>();

		doAnswer(new Answer<String>() {

			@Override
			public String answer(InvocationOnMock invocation) throws Throwable {
				consumer.set((Consumer) invocation.getArguments()[2]);
				return null;
			}
		}).when(onlyChannel)
			.basicConsume(Mockito.anyString(), Mockito.anyBoolean(), Mockito.any(Consumer.class));

		final CountDownLatch commitLatch = new CountDownLatch(1);
		doAnswer(new Answer<String>() {

			@Override
			public String answer(InvocationOnMock invocation) throws Throwable {
				commitLatch.countDown();
				return null;
			}
		}).when(onlyChannel).txCommit();

		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicReference<Channel> exposed = new AtomicReference<Channel>();
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(cachingConnectionFactory);
		container.setMessageListener(new ChannelAwareMessageListener() {
			public void onMessage(Message message, Channel channel) {
				exposed.set(channel);
				RabbitTemplate rabbitTemplate = new RabbitTemplate(cachingConnectionFactory);
				rabbitTemplate.setChannelTransacted(true);
				// should use same channel as container
				rabbitTemplate.convertAndSend("foo", "bar", "baz");
				latch.countDown();
			}

		});
		container.setQueueNames("queue");
		container.setChannelTransacted(true);
		container.setShutdownTimeout(100);
		container.setTransactionManager(new DummyTxManager());
		container.afterPropertiesSet();
		container.start();

		consumer.get().handleDelivery("qux", new Envelope(1, false, "foo", "bar"), new BasicProperties(), new byte[] {0});

		assertTrue(latch.await(10, TimeUnit.SECONDS));

		Exception e = tooManyChannels.get();
		if (e != null) {
			throw e;
		}

		verify(mockConnection, Mockito.times(1)).createChannel();
		assertTrue(commitLatch.await(10, TimeUnit.SECONDS));
		verify(onlyChannel).txCommit();
		verify(onlyChannel).basicPublish(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(),
				Mockito.anyBoolean(), Mockito.any(BasicProperties.class), Mockito.any(byte[].class));
		container.stop();

		assertSame(onlyChannel, exposed.get());
	}

	/**
	 * Verifies that an up-stack RabbitTemplate uses the listener's
	 * channel (ChannelAwareMessageListener). exposeListenerChannel=false
	 * is ignored (ChannelAwareMessageListener).
	 */
	@Test
	public void testChannelAwareMessageListenerDontExpose() throws Exception {
		ConnectionFactory mockConnectionFactory = mock(ConnectionFactory.class);
		Connection mockConnection = mock(Connection.class);
		final Channel onlyChannel = mock(Channel.class);
		when(onlyChannel.isOpen()).thenReturn(true);

		final SingleConnectionFactory cachingConnectionFactory = new SingleConnectionFactory(mockConnectionFactory);

		when(mockConnectionFactory.newConnection((ExecutorService) null)).thenReturn(mockConnection);
		when(mockConnection.isOpen()).thenReturn(true);

		final AtomicReference<Exception> tooManyChannels = new AtomicReference<Exception>();

		doAnswer(new Answer<Channel>(){
			boolean done;
			@Override
			public Channel answer(InvocationOnMock invocation) throws Throwable {
				if (!done) {
					done = true;
					return onlyChannel;
				}
				tooManyChannels.set(new Exception("More than one channel requested"));
				Channel channel = mock(Channel.class);
				when(channel.isOpen()).thenReturn(true);
				return channel;
			}
		}).when(mockConnection).createChannel();

		final AtomicReference<Consumer> consumer = new AtomicReference<Consumer>();

		doAnswer(new Answer<String>() {

			@Override
			public String answer(InvocationOnMock invocation) throws Throwable {
				consumer.set((Consumer) invocation.getArguments()[2]);
				return null;
			}
		}).when(onlyChannel)
			.basicConsume(Mockito.anyString(), Mockito.anyBoolean(), Mockito.any(Consumer.class));

		final CountDownLatch commitLatch = new CountDownLatch(1);
		doAnswer(new Answer<String>() {

			@Override
			public String answer(InvocationOnMock invocation) throws Throwable {
				commitLatch.countDown();
				return null;
			}
		}).when(onlyChannel).txCommit();

		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicReference<Channel> exposed = new AtomicReference<Channel>();
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(cachingConnectionFactory);
		container.setMessageListener(new ChannelAwareMessageListener() {
			public void onMessage(Message message, Channel channel) {
				exposed.set(channel);
				RabbitTemplate rabbitTemplate = new RabbitTemplate(cachingConnectionFactory);
				rabbitTemplate.setChannelTransacted(true);
				// should use same channel as container
				rabbitTemplate.convertAndSend("foo", "bar", "baz");
				latch.countDown();
			}

		});
		container.setQueueNames("queue");
		container.setChannelTransacted(true);
		container.setExposeListenerChannel(false);
		container.setShutdownTimeout(100);
		container.setTransactionManager(new DummyTxManager());
		container.afterPropertiesSet();
		container.start();

		consumer.get().handleDelivery("qux", new Envelope(1, false, "foo", "bar"), new BasicProperties(), new byte[] {0});

		assertTrue(latch.await(10, TimeUnit.SECONDS));

		Exception e = tooManyChannels.get();
		if (e != null) {
			throw e;
		}

		verify(mockConnection, Mockito.times(1)).createChannel();
		assertTrue(commitLatch.await(10, TimeUnit.SECONDS));
		verify(onlyChannel).txCommit();
		verify(onlyChannel).basicPublish(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(),
				Mockito.anyBoolean(), Mockito.any(BasicProperties.class), Mockito.any(byte[].class));
		container.stop();

		assertSame(onlyChannel, exposed.get());
	}

	/**
	 * Verifies the proper channel is bound when using a RabbitTransactionManager.
	 * Previously, the wrong channel was bound. See AMQP-260.
	 * @throws Exception
	 */
	@Test
	public void testMessageListenerWithRabbitTxManager() throws Exception {
		ConnectionFactory mockConnectionFactory = mock(ConnectionFactory.class);
		Connection mockConnection = mock(Connection.class);
		final Channel onlyChannel = mock(Channel.class);
		when(onlyChannel.isOpen()).thenReturn(true);

		final CachingConnectionFactory cachingConnectionFactory = new CachingConnectionFactory(mockConnectionFactory);

		when(mockConnectionFactory.newConnection((ExecutorService) null)).thenReturn(mockConnection);
		when(mockConnection.isOpen()).thenReturn(true);

		final AtomicReference<Exception> tooManyChannels = new AtomicReference<Exception>();

		doAnswer(new Answer<Channel>(){
			boolean done;
			@Override
			public Channel answer(InvocationOnMock invocation) throws Throwable {
				if (!done) {
					done = true;
					return onlyChannel;
				}
				tooManyChannels.set(new Exception("More than one channel requested"));
				Channel channel = mock(Channel.class);
				when(channel.isOpen()).thenReturn(true);
				return channel;
			}
		}).when(mockConnection).createChannel();

		final AtomicReference<Consumer> consumer = new AtomicReference<Consumer>();

		doAnswer(new Answer<String>() {

			@Override
			public String answer(InvocationOnMock invocation) throws Throwable {
				consumer.set((Consumer) invocation.getArguments()[2]);
				return null;
			}
		}).when(onlyChannel)
			.basicConsume(Mockito.anyString(), Mockito.anyBoolean(), Mockito.any(Consumer.class));

		final CountDownLatch commitLatch = new CountDownLatch(1);
		doAnswer(new Answer<String>() {

			@Override
			public String answer(InvocationOnMock invocation) throws Throwable {
				commitLatch.countDown();
				return null;
			}
		}).when(onlyChannel).txCommit();

		final CountDownLatch latch = new CountDownLatch(1);
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(cachingConnectionFactory);
		container.setMessageListener(new MessageListener() {
			public void onMessage(Message message) {
				RabbitTemplate rabbitTemplate = new RabbitTemplate(cachingConnectionFactory);
				rabbitTemplate.setChannelTransacted(true);
				// should use same channel as container
				rabbitTemplate.convertAndSend("foo", "bar", "baz");
				latch.countDown();
			}
		});
		container.setQueueNames("queue");
		container.setChannelTransacted(true);
		container.setShutdownTimeout(100);
		container.setTransactionManager(new RabbitTransactionManager(cachingConnectionFactory));
		container.afterPropertiesSet();
		container.start();

		consumer.get().handleDelivery("qux", new Envelope(1, false, "foo", "bar"), new BasicProperties(), new byte[] {0});

		assertTrue(latch.await(10, TimeUnit.SECONDS));

		Exception e = tooManyChannels.get();
		if (e != null) {
			throw e;
		}

		verify(mockConnection, Mockito.times(1)).createChannel();
		assertTrue(commitLatch.await(10, TimeUnit.SECONDS));
		verify(onlyChannel).txCommit();
		verify(onlyChannel).basicPublish(Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(),
				Mockito.anyBoolean(), Mockito.any(BasicProperties.class), Mockito.any(byte[].class));
		container.stop();
	}

	@SuppressWarnings("serial")
	private static class DummyTxManager extends AbstractPlatformTransactionManager {

		@Override
		protected Object doGetTransaction() throws TransactionException {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
		}
	}

}