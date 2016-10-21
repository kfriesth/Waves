package scorex.transaction.assets.exchange

import scala.util.Try

import com.google.common.primitives.{Ints, Longs}
import play.api.libs.json.{JsObject, Json}
import scorex.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.EllipticCurveImpl
import scorex.crypto.encode.Base58
import scorex.crypto.hash.FastCryptographicHash
import scorex.serialization.{BytesSerializable, Deser}
import scorex.transaction.TypedTransaction.TransactionType
import scorex.transaction._
import scorex.transaction.assets.exchange.Validation.BooleanOperators


/**
  * Transaction with matched orders generated by Matcher service
  */
case class OrderMatch(buyOrder: Order, sellOrder: Order, price: Long, amount: Long, buyMatcherFee: Long,
                      sellMatcherFee: Long, fee: Long, timestamp: Long, signature: Array[Byte])
  extends SignedTransaction with BytesSerializable {

  override val transactionType: TransactionType.Value = TransactionType.OrderMatchTransaction

  override lazy val id: Array[Byte] = FastCryptographicHash(toSign)

  override val assetFee: (Option[AssetId], Long) = (None, fee)

  override val sender: PublicKeyAccount = buyOrder.matcher

  def isValid(previousMatches: Set[OrderMatch]): Validation =  {
    lazy val buyTransactions = previousMatches.filter { om =>
      om.buyOrder.id sameElements buyOrder.id
    }
    lazy val sellTransactions = previousMatches.filter { om =>
      om.sellOrder.id sameElements sellOrder.id
    }

    lazy val buyTotal = buyTransactions.foldLeft(0L)(_ + _.amount) + amount
    lazy val sellTotal = sellTransactions.foldLeft(0L)(_ + _.amount) + amount

    lazy val buyFeeTotal = buyTransactions.map(_.buyMatcherFee).sum + buyMatcherFee
    lazy val sellFeeTotal = sellTransactions.map(_.sellMatcherFee).sum + sellMatcherFee

    lazy val isSameAssets = {
      buyOrder.assetPair == sellOrder.assetPair
    }

    lazy val isSameMatchers = {
      buyOrder.matcher == sellOrder.matcher
    }

    lazy val priceIsValid: Boolean = price <= buyOrder.price && price >= sellOrder.price

    lazy val amountIsValid: Boolean = {
      val b = buyTotal <= buyOrder.amount
      val s = sellTotal <= sellOrder.amount
      b && s
    }

    def isFeeValid(fee: Long, feeTotal: Long, amountTotal: Long, maxfee: Long, maxAmount: Long): Boolean = {
      fee > 0 &&
        feeTotal <= BigInt(maxfee) * BigInt(amountTotal) / BigInt(maxAmount)
    }

    (fee > 0) :| "fee should be > 0" &&
      (amount > 0) :| "amount should be > 0" &&
      (price > 0) :| "price should be > 0" &&
      (price < Order.MaxAmount) :| "price too large" &&
      (amount < Order.MaxAmount) :| "amount too large" &&
      (sellMatcherFee < Order.MaxAmount) :| "sellMatcherFee too large" &&
      (buyMatcherFee < Order.MaxAmount) :| "buyMatcherFee too large" &&
      (fee < Order.MaxAmount) :| "fee too large" &&
      (buyOrder.orderType == OrderType.BUY) :| "buyOrder should has OrderType.BUY" &&
      (sellOrder.orderType == OrderType.SELL) :| "sellOrder should has OrderType.SELL" &&
      isSameMatchers :| "order should have same Matchers" &&
      isSameAssets :| "order should have same Assets" &&
      ("buyOrder" |: buyOrder.isValid(timestamp)) &&
      ("sellOrder" |: sellOrder.isValid(timestamp)) &&
      priceIsValid :| "price should be valid" &&
      amountIsValid :| "amount should be valid" &&
      (fee < buyMatcherFee + sellMatcherFee) :| "fee should be < buyMatcherFee + sellMatcherFee" &&
      isFeeValid(buyMatcherFee, buyFeeTotal, buyTotal, buyOrder.matcherFee, buyOrder.amount) :|
        "buyMatcherFee should be valid" &&
      isFeeValid(sellMatcherFee, sellFeeTotal, sellTotal, sellOrder.matcherFee, sellOrder.amount) :|
        "sellMatcherFee should be valid" &&
      signatureValid :|  "matcherSignatureIsValid should be valid"
  }

  lazy val toSign: Array[Byte] = Ints.toByteArray(buyOrder.bytes.length) ++ Ints.toByteArray(sellOrder.bytes.length) ++
    buyOrder.bytes ++ sellOrder.bytes ++ Longs.toByteArray(price) ++ Longs.toByteArray(amount) ++
    Longs.toByteArray(buyMatcherFee) ++ Longs.toByteArray(sellMatcherFee) ++ Longs.toByteArray(fee) ++
    Longs.toByteArray(timestamp)

  override def bytes: Array[Byte] = toSign ++ signature

  override def json: JsObject = Json.obj(
    "order1" -> buyOrder.json,
    "order2" -> sellOrder.json,
    "price" -> price,
    "amount" -> amount,
    "buyMatcherFee" -> buyMatcherFee,
    "sellMatcherFee" -> sellMatcherFee,
    "fee" -> fee,
    "timestamp" -> timestamp,
    "signature" -> Base58.encode(signature)
  )

  override def balanceChanges(): Seq[BalanceChange] = {

    val matcherChange = Seq(BalanceChange(AssetAcc(buyOrder.matcher, None), buyMatcherFee + sellMatcherFee - fee))
    val buyFeeChange = Seq(BalanceChange(AssetAcc(buyOrder.sender, None), -buyMatcherFee))
    val sellFeeChange = Seq(BalanceChange(AssetAcc(sellOrder.sender, None), -sellMatcherFee))

    val exchange = Seq(
      (buyOrder.sender, (buyOrder.spendAssetId, -(BigInt(amount) * Order.PriceConstant / price).longValue())),
      (buyOrder.sender, (buyOrder.receiveAssetId, amount)),
      (sellOrder.sender, (sellOrder.receiveAssetId, (BigInt(amount) * Order.PriceConstant / price).longValue())),
      (sellOrder.sender, (sellOrder.spendAssetId, -amount))
    )

    buyFeeChange ++ sellFeeChange ++ matcherChange ++
      exchange.map(c => BalanceChange(AssetAcc(c._1, Some(c._2._1)), c._2._2))
  }
}

object OrderMatch extends Deser[OrderMatch] {
  override def parseBytes(bytes: Array[Byte]): Try[OrderMatch] = Try {
    val o1Size = Ints.fromByteArray(bytes.slice(0, 4))
    val o2Size = Ints.fromByteArray(bytes.slice(4, 8))
    val o1 = Order.parseBytes(bytes.slice(8, 8 + o1Size)).get
    val o2 = Order.parseBytes(bytes.slice(8 + o1Size, 8 + o1Size + o2Size)).get
    val s = 8 + o1Size + o2Size
    val price = Longs.fromByteArray(bytes.slice(s, s + 8))
    val amount = Longs.fromByteArray(bytes.slice(s + 8, s + 16))
    val buyMatcherFee = Longs.fromByteArray(bytes.slice(s + 16, s + 24))
    val sellMatcherFee = Longs.fromByteArray(bytes.slice(s + 24, s + 32))
    val fee = Longs.fromByteArray(bytes.slice(s + 32, s + 40))
    val timestamp = Longs.fromByteArray(bytes.slice(s + 40, s + 48))
    val signature = bytes.slice(s + 48, bytes.length)
    OrderMatch(o1, o2, price, amount, buyMatcherFee, sellMatcherFee, fee, timestamp, signature)
  }

  def create(matcher: PrivateKeyAccount, buyOrder: Order, sellOrder: Order, price: Long, amount: Long,
             buyMatcherFee: Long, sellMatcherFee: Long, fee: Long, timestamp: Long): OrderMatch = {
    val unsigned = OrderMatch(buyOrder, sellOrder, price, amount, buyMatcherFee, sellMatcherFee, fee, timestamp, Array())
    val sig = EllipticCurveImpl.sign(matcher, unsigned.toSign)
    unsigned.copy(signature = sig)
  }

  def create(matcher: PrivateKeyAccount, buyOrder: Order, sellOrder: Order, price: Long, amount: Long,
             fee: Long, timestamp: Long): OrderMatch = {
    val buyMatcherFee = BigInt(buyOrder.matcherFee) * amount / buyOrder.amount
    val sellMatcherFee = BigInt(sellOrder.matcherFee) * amount / sellOrder.amount
    val unsigned = OrderMatch(buyOrder, sellOrder, price, amount, buyMatcherFee.toLong,
      sellMatcherFee.toLong, fee, timestamp, Array())
    val sig = EllipticCurveImpl.sign(matcher, unsigned.toSign)
    unsigned.copy(signature = sig)
  }
}
