/**
 * Copyright (c) 2013, impossibl.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  * Neither the name of impossibl.com nor the names of its contributors may
 *    be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.impossibl.postgres.protocol.v30;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.ThreadDeathWatcher;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;



public class ServerConnectionShared {

  public class Ref {

    private boolean released;

    public ServerConnectionShared get() {
      return ServerConnectionShared.this;
    }

    public void release() {
      if (!released) {
        released = true;
        ServerConnectionShared.this.release();
      }
    }
  }

  private static ServerConnectionShared instance;

  public static synchronized Ref acquire() {
    if (instance == null) {
      instance = new ServerConnectionShared();
    }
    return instance.addReference();
  }

  private Bootstrap bootstrap;
  private int count = 0;

  Bootstrap getBootstrap() {
    return bootstrap;
  }

  private synchronized Ref addReference() {
    if (count == 0) {
      init();
    }
    count++;
    return new Ref();
  }

  private synchronized void release() {
    if (count == 1) {
      shutdown();
      count = 0;
    }
    else {
      count--;
    }
  }

  private void init() {

    NioEventLoopGroup group = new NioEventLoopGroup(2, new NamedThreadFactory("PG-JDBC EventLoop"));

    bootstrap = new Bootstrap()
        .group(group)
        .channel(NioSocketChannel.class)
        .handler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) {
            ch.pipeline().addLast(
                new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 1, 4, -4, 0),
                new MessageDispatchHandler()
            );
          }
        })
        .option(ChannelOption.TCP_NODELAY, true);

  }

  private Future<?> shutdown() {

    return bootstrap.config().group().shutdownGracefully(10, 100, TimeUnit.MILLISECONDS);
  }

  @SuppressWarnings("deprecation")
  public void waitForShutdown() {

    shutdown().awaitUninterruptibly(10, TimeUnit.SECONDS);

    Thread deathThread = new Thread(() -> {
      try {
        ThreadDeathWatcher.awaitInactivity(5, TimeUnit.SECONDS);
      }
      catch (InterruptedException e) {
        // Ignore
      }
    });

    Thread globalThread = new Thread(() -> {
      try {
        GlobalEventExecutor.INSTANCE.awaitInactivity(5, TimeUnit.SECONDS);
      }
      catch (InterruptedException e) {
        // Ignore
      }
    });

    try {
      globalThread.join(TimeUnit.SECONDS.toMillis(5));
      deathThread.join(TimeUnit.SECONDS.toMillis(5));
    }
    catch (InterruptedException e) {
      // Ignore
    }

  }



  private class NamedThreadFactory implements ThreadFactory {

    private String baseName;
    private AtomicInteger idx = new AtomicInteger(1);

    NamedThreadFactory(String baseName) {
      super();
      this.baseName = baseName;
    }

    @Override
    public Thread newThread(Runnable r) {
      Thread thread = new Thread(r, baseName + " (" + idx.getAndIncrement() + ")");
      thread.setDaemon(true);
      if (thread.getPriority() != Thread.NORM_PRIORITY)
        thread.setPriority(Thread.NORM_PRIORITY);
      return thread;
    }

  }


}
