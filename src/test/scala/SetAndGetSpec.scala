package com.bitlove.memcached.spec

import org.specs.Specification

import com.bitlove.memcached.Memcached

object SetAndGetSpec extends Specification {
  val c         = new Memcached("localhost", 11211)
  val key       = "some key".getBytes

  "Set and Get" should {
    doBefore {
      c.flush()
      c.get(key) must beEqualTo(None)
    }

   "normal" in {
      c.set(key, "blah".getBytes)
      new String(c.get(key).get) must beEqualTo("blah")

      c.set(key, "boom".getBytes)
      new String(c.get(key).get) must beEqualTo("boom")
    }

    "with ttl" in {
      c.set(key, "blah".getBytes, ttl = Some(1))
      new String(c.get(key).get) must beEqualTo("blah")

      Thread.sleep(2000)

      c.get(key) must beEqualTo(None)
    }

    "delete" in {
      c.set(key, "blah".getBytes)
      c.delete(key) must beEqualTo(true)
      c.get(key) must beEqualTo(None)
      c.delete(key) must beEqualTo(false)
    }
  }
}
