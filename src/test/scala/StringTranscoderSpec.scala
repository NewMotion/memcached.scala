package com.bitlove.memcached.spec

import org.specs.Specification

import com.bitlove.memcached.StringTranscoder

object StringTranscoderSpec extends Specification {
  val t = new StringTranscoder

  "this transcoder" in {
    val value   = "foo"
    val encoded = t.encode(value)

    "turns the string into bytes" in {
      new String(encoded.data) must beEqualTo("foo")
    }

    "doesn't set any flags" in {
      encoded.flags must beEqualTo(0)
    }

    "decodes" in {
      t.decode(encoded) must beEqualTo("foo")
    }
  }
}