package org.bitcoingoldj.core;

import com.rfksystems.blake2b.Blake2b;
import org.bitcoingoldj.params.EquihashDTO;

import javax.xml.bind.DatatypeConverter;
import java.io.ByteArrayOutputStream;
import java.util.*;

/// Ported from ZCASH implementation: https://github.com/zcash/zcash/blob/master/qa/rpc-tests/test_framework/equihash.py
public class Equihash {

    private final byte[] header;
    private byte[] nonce;
    private final byte[] solution;
    private final int n;
    private final int k;
    private final String person;

    private final int word_size = 32;
    private final long word_mask = (long)(1 * Math.pow(2, 32) - 1); // (1 << word_size) - 1 overflow

    public Equihash(byte[] header, byte[] nonce, byte[] solution, int n, int k, String person) {
        this.header = header;
        this.nonce = nonce;
        this.solution = solution;
        this.n = n;
        this.k = k;
        this.person = person;
    }

    public Equihash(byte[] header, byte[] solution, int n, int k, String person) {
        this(header, new byte[0], solution, n, k, person);
}

    public Equihash(byte[] header, byte[] nonce, byte[] solution, EquihashDTO params) {
        this(header, nonce, solution, params.getN(), params.getK(), params.getPerson());
    }

    public Equihash(byte[] header, byte[] solution, EquihashDTO params) {
        this(header, new byte[0], solution, params.getN(), params.getK(), params.getPerson());
    }

    public EquihashResult verify() {
        try {
            if(header.length < 108) {
                return new EquihashResult(false, "Header must be at least 108 long");
            }

            if (nonce.length == 0) {
                if(header.length < 140) {
                    return new EquihashResult(false, "Header must contain nonce");
                }

                nonce = Utils.reverseBytes(Arrays.copyOfRange(header, 140-32, 140));
            }

            String nonce1 = DatatypeConverter.printHexBinary(nonce).toLowerCase();
            return is_gbp_valid();
        } catch(Exception ex) {
            return new EquihashResult(false, ex.getMessage());
        }
    }

    private EquihashResult is_gbp_valid() throws Exception {
        validate_params(n, k);
        int collision_length = n / (k + 1);
        int hash_length = (k + 1) * ((collision_length + 7) / 8);
        int indices_per_hash_output = 512 / n;
        int solution_width = (1 << k) * (collision_length + 1) / 8;

        if (solution.length != solution_width) {
            return new EquihashResult(false, "Invalid solution length: " + solution.length + " (expected " + solution_width + ")");
        }

        List<List<long[]>> X = new ArrayList<>();
        List<Long> indices = get_indices_from_minimal(solution, collision_length + 1);
        for (Long indice : indices) {
            long i = indice;
            long r = i % indices_per_hash_output;
            // # X_i = H(I||V||x_i)
            Blake2b curr_digest = createDigest();
            hash_xi(curr_digest, i / indices_per_hash_output);
            final byte[] tmp_hash = new byte[curr_digest.getDigestSize()];
            curr_digest.digest(tmp_hash, 0);
            byte[] slice = Arrays.copyOfRange(tmp_hash, (int)(r * n / 8), (int)((r + 1) * n / 8));
            byte[] extendedArray = expand_array(slice, hash_length, collision_length, 0);
            long[] castedArray = new long[extendedArray.length];
            for (int a = 0; a < extendedArray.length; a++) {
                castedArray[a] = extendedArray[a] & 0xFF; // to unsigned byte - 0xFF;
            }

            List<long[]> innerArray = new ArrayList<>();
            innerArray.add(castedArray);
            innerArray.add(new long[]{i});
            X.add(innerArray);
        }

        for (int r = 1; r < k + 1; r++) {
            List<List<long[]>> Xc = new ArrayList<>();
            for (int i = 0; i < X.size(); i += 2) {
                if (!has_collision(X.get(i).get(0), X.get(i + 1).get(0), r, collision_length)) {
                    return new EquihashResult(false, "Invalid solution: invalid collision length between StepRow");
                }
                if (X.get(i + 1).get(1)[0] < X.get(i).get(1)[0]) {
                    return new EquihashResult(false, "Invalid solution: Index tree incorrectly ordered");
                }
                if (!distinct_indices(X.get(i).get(1), X.get(i + 1).get(1))) {
                    return new EquihashResult(false, "Invalid solution: duplicate indices");
                }

                long[] xorArray = xor(X.get(i).get(0), X.get(i + 1).get(0));
                long[] unionArray = union(X.get(i).get(1), X.get(i + 1).get(1));

                List<long[]> innerArray = new ArrayList<>();
                innerArray.add(xorArray);
                innerArray.add(unionArray);
                Xc.add(innerArray);
            }

            X = Xc;
        }

        if (X.size() != 1) {
            return new EquihashResult(false, "Invalid solution: incorrect length after end of rounds: " + X.size());
        }

        if (count_zeroes(X.get(0).get(0)) != 8 * hash_length) {
            return new EquihashResult(false, "Invalid solution: incorrect number of zeroes: " + count_zeroes(X.get(0).get(0)));
        }

        return new EquihashResult(true);
    }

    private  void validate_params(int n, int k) {
        if (k >= n) {
            throw new IllegalArgumentException("n must be larger than k");
        }
        if (((n / (k + 1)) + 1) >= 32) {
            throw new IllegalArgumentException("Parameters must satisfy n/(k+1)+1 < 32");
        }
    }

    private int count_zeroes(long[] h) {
        // # Convert to binary string
        String zeroPad = "00000000";
        StringBuilder res = new StringBuilder();
        for (long aH : h) {
            String binary = Long.toBinaryString(aH);
            res.append(zeroPad.substring(binary.length())).append(binary);
        }

        // # Count leading zeroes
        return (res.toString() + '1').indexOf('1');
    }

    private long[] union(long[] ha, long[] hb) {
        List<Long> res = new ArrayList<>();
        for (long a: ha) {
            if (!res.contains(a)) {
                res.add(a);
            }
        }
        for (long b: hb) {
            if (!res.contains(b)) {
                res.add(b);
            }
        }

        return toPrimitiveArray(res);
    }

    private long[] xor(long[] ha, long[] hb) {
        if (ha.length != hb.length) {
            throw new IllegalArgumentException("ha.length != hb.length");
        }

        List<Map.Entry<Long,Long>> zip = new ArrayList<>(ha.length);
        for (int i = 0; i < ha.length; ++i) {
            zip.add(new AbstractMap.SimpleEntry<>(ha[i], hb[i]));
        }

        List<Long> res = new ArrayList<>();
        for (Map.Entry<Long, Long> aZip : zip) {
            res.add(aZip.getKey() ^ aZip.getValue());
        }

        return toPrimitiveArray(res);
    }

    private <T extends Collection<Long>> long[] toPrimitiveArray(T res) {
        long[] result = new long[res.size()];
        Long[] objectArray = res.toArray(new Long[0]);
        for (int i = 0; i < res.size(); i++) {
            result[i] = objectArray[i];
        }

        return result;
    }

    private boolean distinct_indices(long[] a, long[] b) {
        for (long anA : a) {
            for (long aB : b) {
                if (anA == aB) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean has_collision(long[] ha, long[] hb, int i, int l) {
        for (int j = ((i - 1) * l / 8); j < (i * l / 8); j++) {
            if(ha[j] != hb[j]) {
                return false;
            }
        }

        return true;
    }

    private Blake2b hash_xi(Blake2b digest, long xi) throws Exception {
        ByteArrayOutputStream buf = new UnsafeByteArrayOutputStream(4);
        Utils.uint32ToByteStreamLE(xi, buf);
        digest.update(buf.toByteArray(), 0, 4);
        return digest; //# For chaining
    }

    private List<Long> get_indices_from_minimal(byte[] minimal, int bit_len) {
        int eh_index_size = 4;
        if ((bit_len + 7) / 8 > eh_index_size) {
            throw new IllegalArgumentException("(bit_len + 7) / 8 > eh_index_size");
        }

        int len_indices = 8 * eh_index_size * minimal.length / bit_len;
        int byte_pad = eh_index_size - ((bit_len + 7) / 8);
        byte[] expanded = expand_array(minimal, len_indices, bit_len, byte_pad);

        List<Long> data = new ArrayList<>();
        for (int i = 0; i < len_indices; i += eh_index_size) {
            data.add(Utils.readUint32BE(expanded, i));
        }

        return data;
    }

    private byte[] expand_array(byte[] inp, int out_len, int bit_len, int byte_pad) {
        if (bit_len < 8 || word_size < 7 + bit_len)
        {
            throw new IllegalArgumentException("bit_len < 8 || word_size < 7 + bit_len");
        }

        int out_width = ((bit_len + 7) / 8) + byte_pad;
        if (out_len != (8 * out_width * inp.length / bit_len))
        {
            throw new IllegalArgumentException("out_len != (8 * out_width * inp.length / bit_len)");
        }

        byte[] out = new byte[out_len];

        int bit_len_mask = (1 << bit_len) - 1;

        // # The acc_bits least-significant bits of acc_value represent a bit sequence
        // # in big-endian order.
        int acc_bits = 0;
        long acc_value = 0;

        int j = 0;
        for (byte anInp : inp) {
            acc_value = ((acc_value << 8) & word_mask) | (anInp & 0xFF); // to unsigned byte - 0xFF
            acc_bits += 8;

            // # When we have bit_len or more bits in the accumulator, write the next
            // # output element.
            if (acc_bits >= bit_len) {
                acc_bits -= bit_len;
                for (int x = byte_pad; x < out_width; x++) {
                    out[j + x] =(byte)(
                        (
                                // # Big-endian
                                acc_value >> (acc_bits + (8 * (out_width - x - 1)))
                        ) & (
                                // # Apply bit_len_mask across byte boundaries
                                (bit_len_mask >> (8 * (out_width - x - 1))) & 0xFF
                        )
                    );
                }

                j += out_width;
            }
        }

        return out;
    }

    private byte[] getPerson(int n, int k, String person) throws Exception {
        ByteArrayOutputStream personStream = new UnsafeByteArrayOutputStream(16);
        personStream.write(person.getBytes());
        Utils.uint32ToByteStreamLE(n, personStream);
        Utils.uint32ToByteStreamLE(k, personStream);

        return personStream.toByteArray();
    }

    private Blake2b createDigest() throws Exception{
        int digestLen = (int)(Math.floor((512 / n)) * Math.floor(n / 8));
        byte[] personBytes = getPerson(n, k, person);
        Blake2b blake = new Blake2b(null, digestLen, null, personBytes);
        blake.update(header, 0, 108);

        // hash_nonce
        for (int i = 7; i >= 0; i--) {
            ByteArrayOutputStream buf = new UnsafeByteArrayOutputStream(4);
            long num = Utils.readUint32BE(nonce, 4 * i);
            Utils.uint32ToByteStreamLE(num, buf);
            blake.update(buf.toByteArray(), 0, 4);
        }

        return blake;
    }
}
