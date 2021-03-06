package com.bitlove.memcached

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.net.InetSocketAddress
import com.bitlove.memcached.transcoding.{Transcoder, Transcodable, ByteArrayTranscoder}
import com.bitlove.memcached.protocol._

class Memcached[X](host: String,
                   port: Int,
                   defaultTranscoder: Transcoder[X, Array[Byte]] = new ByteArrayTranscoder) {
  class ProtocolError(message: String) extends Error(message)
  class ValueTooLarge                  extends Error("Value too large")
  class BadIncrDecr                    extends Error("Incr/decr on non-numeric value")

  private val addr    = new InetSocketAddress(host, port)
  private val channel = SocketChannel.open(addr)
  private val header  = ByteBuffer.allocate(24)

  /**
   * Appends data to an existing key.
   * @param key The key to append to.
   * @param value The data to append.
   * @param transcoder The transcoder to use. Note that flags are not sent in append/prepend requests.
   * @return true if the data was appended, false if the key did not exist
   */
  def append[T](key:   Array[Byte],
                value: T)
                (implicit transcoder: Transcoder[T, Array[Byte]] = defaultTranscoder): Boolean = {
    val encoded = transcoder.encode(value)
    channel.write(RequestBuilder.appendOrPrepend(Ops.Append, key, encoded.data))
    handleResponse(Ops.Append, handleStorageResponse)
  }

  /**
   * Prepends data to an existing key.
   * @param key The key to prepend to.
   * @param value The data to prepend.
   * @param transcoder The transcoder to use. Note that flags are not sent in append/prepend requests.
   * @return true if the data was prepended, false if the key did not exist.
   */
  def prepend[T](key:   Array[Byte],
                 value: T)
                 (implicit transcoder: Transcoder[T, Array[Byte]] = defaultTranscoder): Boolean = {
    val encoded = transcoder.encode(value)
    channel.write(RequestBuilder.appendOrPrepend(Ops.Prepend, key, encoded.data))
    handleResponse(Ops.Prepend, handleStorageResponse)
  }

  /**
   * Increments a numeric key
   * @param key The key to increment
   * @param count The amount to increment by
   * @param default If the key does not exist, set it to this.
   * @param ttl If a default value is supplied and the key did not exist, expire it after this ttl in seconds.
   * @return None if the key did not exist, otherwise Some(BigInt) with the new incremented number
   */
  def incr(key:     Array[Byte],
           count:   Long           = 1,
           ttl:     Option[Int]    = None,
           default: Option[BigInt] = None): Option[BigInt] = {
    channel.write(RequestBuilder.incrOrDecr(Ops.Increment,
                                            key,
                                            count,
                                            ttl,
                                            default))
    handleResponse(Ops.Increment, handleIncrDecrResponse)
  }

  /**
   * Decrements a numeric key
   * @param key The key to decrement
   * @param count The amount to decrement by
   * @param default If the key does not exist, set it to this.
   * @param ttl If a default value is supplied and the key did not exist, expire it after this ttl in seconds.
   * @return None if the key did not exist, otherwise Some(BigInt) with the new decremented number
   */
  def decr(key:     Array[Byte],
           count:   Long           = 1,
           ttl:     Option[Int]    = None,
           default: Option[BigInt] = None): Option[BigInt] = {
    channel.write(RequestBuilder.incrOrDecr(Ops.Decrement,
                                            key,
                                            count,
                                            ttl,
                                            default))
    handleResponse(Ops.Decrement, handleIncrDecrResponse)
  }

  /**
   * Flushes all items in the cache.
   * @param after If given, flush the cache after this many seconds instead of immediately.
   */
  def flush(after: Option[Int] = None): Unit = {
    channel.write(RequestBuilder.flush(after))
    handleResponse(Ops.Flush, handleEmptyResponse)
  }

  def noop: Unit = {
    channel.write(RequestBuilder.noop)
    handleResponse(Ops.NoOp, handleEmptyResponse)
  }

  /**
   * Get a key from the cache.
   * @param key The key to fetch.
   * @param transcoder Transcoder to use for this request.
   * @return Some[T] if the key was present, otherwise None
   */
  def get[T](key: Array[Byte])
            (implicit transcoder: Transcoder[T, Array[Byte]] = defaultTranscoder): Option[T] = {
    channel.write(RequestBuilder.get(key))
    handleResponse(Ops.Get, getResponseHandlerFactory(transcoder))
  }

  /**
   * Do a compare-and-swap operation.
   *
   * The supplied function will be called with the current value of the key.
   * It may return Some[T] to attempt to CAS the key to this value,
   * or None to attempt to delete it.
   *
   * In the example, if the key is presently set to "foo",
   * it will be set to "bar". If it is any other value, it
   * will be deleted.
   *
   * {{{
   * client.cas[String]("myKey".getBytes) { value =>
   *   value match {
   *     case Some("foo") => Some("bar")
   *     case _           => None
   *   }
   * }
   * }}}
   *
   * @param key The key to CAS.
   * @param ttl Optional TTL. If present and > 0, and the CAS succeeds, the key will expire after this many seconds.
   * @param f CAS function
   * @param transcoder Transcoder to use for this request.
   * @return true if the CAS succeeded and a key was modified, otherwise false
   */
  def cas[T](key: Array[Byte],
             ttl: Option[Int] = None)
            (f: Option[T] => Option[T])
            (implicit transcoder: Transcoder[T, Array[Byte]] = defaultTranscoder): Boolean = {
    channel.write(RequestBuilder.get(key))
    val (casId, originalValue) = handleResponse(Ops.Get, getForCasResponseHandlerFactory(transcoder))
    val newValue               = f(originalValue)

    newValue match {
      case None        => delete(key, Some(casId))
      case Some(thing) => {
        originalValue match {
          case None    => add(key, thing, ttl)(transcoder)
          case Some(_) => set(key, thing, ttl, Some(casId))(transcoder)
        }
      }
    }
  }

  /**
   * Set a key.
   * @param key The key to set.
   * @param value The value to set it to.
   * @param ttl Optional TTL. If present and > 0, the key will expire after this many seconds.
   * @param casId Optional CAS value. If present and > 0, the operation will fail unless this value matches the current CAS value for the key on the server.
   * @param transcoder Transcoder to use for this request.
   * @return true if the key was set, otherwise false.
   */
  def set[T](key:   Array[Byte],
             value: T,
             ttl:   Option[Int]  = None,
             casId: Option[Long] = None)
            (implicit transcoder: Transcoder[T, Array[Byte]] = defaultTranscoder) = {
    val encoded = transcoder.encode(value)
    channel.write(RequestBuilder.storageRequest(Ops.Set,
                                                key,
                                                encoded.data,
                                                encoded.flags,
                                                ttl,
                                                casId))
    handleResponse(Ops.Set, handleStorageResponse)
  }

  /**
   * Add a key, only if it doesn't already exist.
   * @param key The key to add.
   * @param value The value to set it to.
   * @param ttl Optional TTL. If present and > 0, and the add succeeds, the key will expire after this many seconds.
   * @param transcoder Transcoder to use for this request.
   * @return true if the key did not already exist and the add succeeded, otherwise false
   */
  def add[T](key:   Array[Byte],
             value: T,
             ttl:   Option[Int] = None)
            (implicit transcoder: Transcoder[T, Array[Byte]] = defaultTranscoder): Boolean = {
    val encoded = transcoder.encode(value)
    channel.write(RequestBuilder.storageRequest(Ops.Add,
                                                key,
                                                encoded.data,
                                                encoded.flags,
                                                ttl))
    handleResponse(Ops.Add, handleStorageResponse)
  }

  /**
   * Replace a key, only if it already exists.
   * @param key The key to replace.
   * @param value The value to set it to.
   * @param ttl Optional TTL. If present and > 0, and the replace succeeds, the key will expire after this many seconds.
   * @param casId Optional CAS Value. If present and > 0, the operation will fail unless this value matches the current CAS value for the key on the server.
   * @param transcoder Transcoder to use for this request.
   * @return true if the key existed and was replaced, otherwise false.
   */
  def replace[T](key:   Array[Byte],
                 value: T,
                 ttl:   Option[Int]  = None,
                 casId: Option[Long] = None)
                (implicit transcoder: Transcoder[T, Array[Byte]] = defaultTranscoder): Boolean = {
    val encoded = transcoder.encode(value)
    channel.write(RequestBuilder.storageRequest(Ops.Replace,
                                                key,
                                                encoded.data,
                                                encoded.flags,
                                                ttl,
                                                casId))
    handleResponse(Ops.Replace, handleStorageResponse)
  }

  /**
   * Delete a key.
   * @param key The key to delete.
   * @param casId Optional CAS Value. If present and > 0, the operation will fail unless this value matches the current CAS value for the key on the server.
   * @return true if the key was deleted, otherwise false.
   */
  def delete(key:   Array[Byte],
             casId: Option[Long] = None): Boolean = {
    channel.write(RequestBuilder.delete(key, casId))
    handleResponse(Ops.Delete, handleStorageResponse)
  }

  def isConnected: Boolean = {
    channel.isConnected
  }

  def close = {
    try {
      channel.write(RequestBuilder.quit)
      channel.close
    } catch {
      case _ => ()
    }
  }

  private def handleIncrDecrResponse(header: ByteBuffer, body: ByteBuffer): Option[BigInt] = {
    header.getShort(6) match {
      case Status.Success     => Some(BigInt(1, body.array))
      case Status.KeyNotFound => None
      case Status.BadIncrDecr => throw new BadIncrDecr
      case code               => throw new ProtocolError("Unexpected status code %d".format(code))
    }
  }

  private def handleEmptyResponse(header: ByteBuffer, body: ByteBuffer): Unit = {
    header.getShort(6) match {
      case Status.Success  => ()
      case code            => throw new ProtocolError("Unexpected status code %d".format(code))
    }
  }

  private def handleStorageResponse(header: ByteBuffer, body: ByteBuffer): Boolean = {
    header.getShort(6) match {
      case Status.Success     => true
      case Status.KeyNotFound => false
      case Status.KeyExists   => false
      case Status.NotStored   => false
      case Status.TooLarge    => throw new ValueTooLarge
      case code               => throw new ProtocolError("Unexpected status code %d".format(code))
    }
  }

  private def getResponseHandlerFactory[T](transcoder: Transcoder[T, Array[Byte]]): (ByteBuffer, ByteBuffer) => Option[T] = {
    (header: ByteBuffer, body: ByteBuffer) => {
      header.getShort(6) match {
        case Status.Success => {
          val extrasLen  = header.get(4).toInt
          val encoded    = new Transcodable(data  = body.array.slice(extrasLen, body.capacity),
                                            flags = body.getInt(0))
          Some(transcoder.decode(encoded).data)
        }
        case _ => None
      }
    }
  }

  private def getForCasResponseHandlerFactory[T](transcoder: Transcoder[T, Array[Byte]]): (ByteBuffer, ByteBuffer) => (Long, Option[T]) = {
    (header: ByteBuffer, body: ByteBuffer) => {
      (header.getLong(16), getResponseHandlerFactory(transcoder)(header, body))
    }
  }

  private def handleResponse[T](opcode:  Byte,
                                handler: (ByteBuffer, ByteBuffer) => T): T = {
    fillHeader
    verifyMagic
    verifyOpcode(opcode)
    handler(header, fillBodyFromHeader)
  }

  private def fillHeader: Unit = {
    header.clear
    fill(header, 24)
  }

  private def fillBodyFromHeader: ByteBuffer = {
    val len  = header.getInt(8)
    val body = ByteBuffer.allocate(len)
    fill(body, len)
    body
  }

  private def fill(buffer: ByteBuffer, len: Int): Unit = {
    var read = 0

    while (read < len) {
      read += channel.read(buffer)
    }

    buffer.flip
  }

  private def verifyMagic = {
    header.get(0) match {
      case Packets.Response => ()
      case otherByte        => {
        throw new ProtocolError("Unexpected header magic 0x%x".format(otherByte))
      }
    }
  }

  private def verifyOpcode(opcode: Byte) = {
    header.get(1) match {
      case x if x == opcode => ()
      case otherByte        => {
        throw new ProtocolError("Unexpected opcode 0x%x".format(otherByte))
      }
    }
  }
}
