#!/usr/bin/python
import numpy as np
from scipy.linalg import block_diag

# Some hacky code for debugging the projective transforms.

P_android = np.array(
    [[ 0.9998574,  -0.008127,    0.01480291,  0.05063377],
     [-0.00816306, -0.99996386,  0.00237692,  0.08271678],
     [ 0.01478306, -0.00249742, -0.99988761, -0.0021191 ],
     [ 0. ,         0.        ,  0.       ,   1.        ]]
     )
P_kalibr = np.array(
    [[ 0.99995753, 0.00257981, -0.00884737, -0.04714906],
    [ 0.00297671, -0.99897596, 0.045146, 0.03065534],
    [-0.00872184, -0.04517042, -0.99894122, 0.00045755],
    [ 0., 0., 0., 1.]]
)

# P_kalibr = np.array(
#     [[ 0.99998509, 0.00437722, -0.00326597, -0.04863791],
#     [ 0.00451933, -0.99898326, 0.04485548, 0.02672486],
#     [-0.0030663, -0.04486957, -0.99898815, -0.00581091],
#     [ 0., 0., 0., 1.]]
# )
P_kalibr_270 = np.array(
    [[ 0.00494602, -0.99891338, 0.04634225, 0.02392655],
    [-0.99995915, -0.00458995, 0.00778673, 0.04756003],
    [-0.00756556, -0.04637887, -0.99889527, -0.00041323],
    [ 0., 0., 0., 1.]]
)
#Solve DLT for X*P_android = P_kalibr, i.e. P_android.T*X.T = P_kalibr.T
if __name__ == '__main__':
    P = P_android.T
    P_target = P_kalibr
    rows = [block_diag(*[P[r]]*4) for r in range(4)]
    # for r in range(4):
    #     rows.append(
    #         block_diag([P[r]]*4)
    #     )
    A = np.vstack(rows)
    b = P_target.T.flatten(order='F')
    X_T, res, rnk, s = np.linalg.lstsq(A,b)
    X = X_T.reshape([4,4], order='F').T
    print(X)
    print(np.matmul(X,P)-P_target)
    print(np.linalg.norm(np.matmul(X,P)-P_target))

    x_w = np.array([1, 2, -3, 1])
    x_c = np.matmul(P_android, x_w)
    x_c /= x_c[-1]
    print(x_c)
