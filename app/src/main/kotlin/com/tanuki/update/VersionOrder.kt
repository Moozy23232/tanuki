package com.tanuki.update

/** SemVer order, including numeric prerelease identifiers (rc.10 sorts after rc.9). */
internal object VersionOrder {
    fun compare(left: String, right: String): Int {
        fun parts(value:String):Pair<List<java.math.BigInteger>,List<String>> {
            require(value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")))
            return value.substringBefore('-').split('.').map { it.toBigInteger() } to
                if('-' in value)value.substringAfter('-').split('.') else emptyList()
        }
        val (a,ap)=parts(left);val (b,bp)=parts(right)
        for(i in a.indices) { val order=a[i].compareTo(b[i]);if(order!=0)return order }
        if(ap.isEmpty() || bp.isEmpty())return when {ap.isEmpty() && bp.isEmpty()->0;ap.isEmpty()->1;else->-1}
        for(i in 0 until minOf(ap.size,bp.size)) {
            val x=ap[i];val y=bp[i]
            val xn=x.all(Char::isDigit);val yn=y.all(Char::isDigit)
            val order=when {xn && yn->x.toBigInteger().compareTo(y.toBigInteger());xn->-1;yn->1;else->x.compareTo(y)}
            if(order!=0)return order
        }
        return ap.size.compareTo(bp.size)
    }
}
